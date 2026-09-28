/*
 * Copyright (c) 2026. The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.client.api;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.git.GitRepoFiles;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.code.context.ondemand.OnDemandCodeContextTools;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.AiRequestCancellation;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.errors.exceptions.AiRequestSupersededException;
import com.googlesource.gerrit.plugins.reviewai.logging.LogArg;
import com.googlesource.gerrit.plugins.reviewai.metrics.cost.AiCostTracker;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
class LangChainExecutor {

  // Rounds that answer late tool calls with a rejection, keeping the conversation.
  static final int MAX_FINAL_ANSWER_ATTEMPTS = 1;
  // Fresh requests without tools that carry the tool results as text, after the rejection round
  // did not produce an answer (more tool calls or an empty reply).
  static final int MAX_DIGEST_ATTEMPTS = 2;
  static final int DIGEST_MAX_RESULT_CHARS = 20_000;
  static final int DIGEST_MAX_TOTAL_CHARS = 150_000;
  static final int LOGGED_ANSWER_MAX_CHARS = 300;
  private static final Pattern EMPTY_JSON_OBJECT =
      Pattern.compile("\\s*(?:```(?:json)?\\s*)?\\{\\s*}\\s*(?:```)?\\s*");
  static final String DIGEST_INSTRUCTION =
      "[ReviewAI: the tool budget is used up and tools are no longer available. Below are the"
          + " results of the lookups you made. Do not ask for more context. Return your final"
          + " answer now, in the required format, based on the review request above and these"
          + " results.]";
  static final String TOOL_BUDGET_NOTE =
      "[ReviewAI tool budget: round %d of %d used, %d left. Request all the lookups you still"
          + " need in one round; answer as soon as you have enough context.]";
  static final String TOOL_BUDGET_LAST_ROUND =
      "[ReviewAI tool budget: this was the last tool round. Tool calls are no longer executed;"
          + " return your final answer now.]";
  static final String TOOL_BUDGET_EXHAUSTED =
      "REJECTED: the tool budget of %d rounds is used up and this call was not executed. Do not"
          + " call any tool again. Return your final answer now, in the required format, based on"
          + " the context you already have.";

  private final Configuration config;
  private final ResponseFormat structuredResponseFormat;
  private final List<ToolSpecification> onDemandTools;
  private final boolean requireInitialToolUse;
  private final GitRepoFiles gitRepoFiles;
  private final AiCostTracker costTracker;

  AiMessage execute(ChatModel model, GerritChange change, ChatMemory memory) {
    return execute(model, change, new ChangeSetData(0), memory);
  }

  AiMessage execute(
      ChatModel model, GerritChange change, ChangeSetData changeSetData, ChatMemory memory) {
    return execute(model, null, change, changeSetData, memory);
  }

  /**
   * @param finalAnswerModel the same model set up to answer quickly, for the requests that ask for
   *     the final answer after the tool budget; null uses {@code model}
   */
  AiMessage execute(
      ChatModel model,
      ChatModel finalAnswerModel,
      GerritChange change,
      ChangeSetData changeSetData,
      ChatMemory memory) {
    AiRequestCancellation cancellation = changeSetData.getAiRequestCancellation();
    cancellation.throwIfSupersessionRequested();
    log.debug(
        "Starting LangChain execution with {} memory messages, initialToolChoice={}, tools={}, "
            + "structuredResponse={}",
        memory.messages().size(),
        getInitialToolChoice(),
        getToolNames(),
        structuredResponseFormat != null);
    List<ChatMessage> requestMessages = new ArrayList<>(memory.messages());
    List<ChatMessage> initialMessages = List.copyOf(requestMessages);
    List<String> toolTranscript = new ArrayList<>();
    ChatRequest initialRequest = buildChatRequest(requestMessages, getInitialToolChoice());
    log.debug("Sending initial LangChain chat request: {}", LogArg.truncated(initialRequest));
    ChatResponse response = AiModelRequestLimiter.chat(config, model, initialRequest);
    recordCost(response, change, changeSetData);
    AiMessage aiMessage = response != null ? response.aiMessage() : null;
    logAiMessageToolRequests("initial", aiMessage);
    int maxToolResponseRounds = config.getAiMaxToolResponseRounds();
    int iteration = 0;
    while (aiMessage != null
        && aiMessage.hasToolExecutionRequests()
        && iteration < maxToolResponseRounds) {
      iteration++;
      requestMessages.add(aiMessage);
      memory.add(aiMessage);
      List<ToolExecutionRequest> requests = aiMessage.toolExecutionRequests();
      if (requests == null || requests.isEmpty()) {
        break;
      }
      log.debug(
          "Processing LangChain tool response round {} of {} with {} tool requests",
          iteration,
          maxToolResponseRounds,
          requests.size());
      for (ToolExecutionRequest request : requests) {
        String output =
            withToolBudgetNote(
                executeToolRequest(request, change, changeSetData),
                iteration,
                maxToolResponseRounds);
        log.debug(
            "Adding LangChain tool result for request id={}, name={}, outputLength={}",
            request.id(),
            request.name(),
            output != null ? output.length() : 0);
        toolTranscript.add(transcriptEntry(request, output));
        ToolExecutionResultMessage toolResult = ToolExecutionResultMessage.from(request, output);
        requestMessages.add(toolResult);
        memory.add(toolResult);
      }
      log.debug(
          "Sending LangChain continuation request after tool round {} with {} memory messages "
              + "and {} request messages",
          iteration,
          memory.messages().size(),
          requestMessages.size());
      response =
          AiModelRequestLimiter.chat(
              config,
              model,
              buildChatRequest(
                  requestMessages,
                  iteration == maxToolResponseRounds ? ToolChoice.NONE : ToolChoice.AUTO));
      recordCost(response, change, changeSetData);
      aiMessage = response != null ? response.aiMessage() : null;
      logAiMessageToolRequests("tool-continuation-" + iteration, aiMessage);
    }
    log.debug("Received LangChain response message: {}", aiMessage);

    // Models don't always honour ToolChoice.NONE (Gemini 3 kept calling tools). Reject the calls
    // with an explicit tool result and ask for the final answer, instead of ending with nothing.
    ChatModel answerModel = finalAnswerModel != null ? finalAnswerModel : model;
    int finalAnswerAttempts = 0;
    while (aiMessage != null
        && aiMessage.hasToolExecutionRequests()
        && finalAnswerAttempts < MAX_FINAL_ANSWER_ATTEMPTS) {
      finalAnswerAttempts++;
      log.info(
          "Tool budget of {} rounds used up; rejecting {} tool requests and asking for the final"
              + " answer (attempt {} of {})",
          maxToolResponseRounds,
          aiMessage.toolExecutionRequests().size(),
          finalAnswerAttempts,
          MAX_FINAL_ANSWER_ATTEMPTS);
      requestMessages.add(aiMessage);
      memory.add(aiMessage);
      for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
        ToolExecutionResultMessage rejection =
            ToolExecutionResultMessage.from(
                request, String.format(TOOL_BUDGET_EXHAUSTED, maxToolResponseRounds));
        requestMessages.add(rejection);
        memory.add(rejection);
      }
      response =
          chatForAnswer(model, answerModel, buildChatRequest(requestMessages, ToolChoice.NONE));
      recordCost(response, change, changeSetData);
      aiMessage = response != null ? response.aiMessage() : null;
      logAiMessageToolRequests("final-answer-" + finalAnswerAttempts, aiMessage);
    }

    // Still no answer: start over without tools and without the function-call history (which
    // needs thought signatures and invites more calls), giving the tool results as text. A review
    // that used up its tool budget is otherwise paid for and lost.
    int digestAttempts = 0;
    while (needsFinalAnswer(aiMessage, iteration) && digestAttempts < MAX_DIGEST_ATTEMPTS) {
      digestAttempts++;
      log.info(
          "No final answer after {} tool rounds ({}); asking without tools, with {} tool results"
              + " as text (attempt {} of {})",
          iteration,
          aiMessage.hasToolExecutionRequests() ? "tool calls pending" : "empty reply",
          toolTranscript.size(),
          digestAttempts,
          MAX_DIGEST_ATTEMPTS);
      response =
          chatForAnswer(model, answerModel, buildDigestRequest(initialMessages, toolTranscript));
      recordCost(response, change, changeSetData);
      aiMessage = response != null ? response.aiMessage() : null;
      logAiMessageToolRequests("digest-answer-" + digestAttempts, aiMessage);
    }
    if (iteration > 0) {
      logFinalAnswer(iteration, aiMessage);
    }

    if (aiMessage != null && aiMessage.hasToolExecutionRequests()) {
      log.warn(
          "LangChain tool execution stopped after {} rounds with pending tool requests: {}",
          maxToolResponseRounds,
          aiMessage.toolExecutionRequests());
    }

    log.debug(
        "Finished LangChain execution after {} rounds; responsePresent={}, pendingToolRequests={}",
        iteration,
        aiMessage != null,
        aiMessage != null && aiMessage.hasToolExecutionRequests());
    return aiMessage;
  }

  /** Sends a final-answer request to the quick model, falling back to the review model. */
  private ChatResponse chatForAnswer(ChatModel model, ChatModel answerModel, ChatRequest request) {
    if (answerModel == model) {
      return AiModelRequestLimiter.chat(config, model, request);
    }
    try {
      return AiModelRequestLimiter.chat(config, answerModel, request);
    } catch (AiRequestSupersededException e) {
      throw e;
    } catch (RuntimeException e) {
      log.warn(
          "Final-answer request with the quick-answer model failed, retrying with the review"
              + " model: {}",
          e.getMessage());
      return AiModelRequestLimiter.chat(config, model, request);
    }
  }

  /**
   * A reply after tool rounds that is no answer: more tool calls, no text, or an empty JSON object
   * (Gemini 3 Flash answered "{}" after 8 tool rounds, without the required fields).
   */
  private static boolean needsFinalAnswer(AiMessage aiMessage, int toolRounds) {
    if (aiMessage == null || toolRounds == 0) {
      return false;
    }
    return aiMessage.hasToolExecutionRequests()
        || aiMessage.text() == null
        || aiMessage.text().isBlank()
        || EMPTY_JSON_OBJECT.matcher(aiMessage.text()).matches();
  }

  private static String transcriptEntry(ToolExecutionRequest request, String output) {
    String text = output == null ? "" : output;
    if (text.length() > DIGEST_MAX_RESULT_CHARS) {
      text = text.substring(0, DIGEST_MAX_RESULT_CHARS) + "\n[... cut]";
    }
    return "### " + request.name() + " " + request.arguments() + "\n" + text;
  }

  /** The original request plus one user message with the tool results, and no tools. */
  static ChatRequest buildDigestRequest(List<ChatMessage> initialMessages, List<String> transcript) {
    StringBuilder digest = new StringBuilder(DIGEST_INSTRUCTION);
    int omitted = 0;
    for (String entry : transcript) {
      if (digest.length() + entry.length() > DIGEST_MAX_TOTAL_CHARS) {
        omitted++;
        continue;
      }
      digest.append("\n\n").append(entry);
    }
    if (omitted > 0) {
      digest.append("\n\n[").append(omitted).append(" more tool results omitted for size.]");
    }
    List<ChatMessage> messages = new ArrayList<>(initialMessages);
    messages.add(UserMessage.from(digest.toString()));
    return ChatRequest.builder().messages(messages).build();
  }

  private static void logFinalAnswer(int toolRounds, AiMessage aiMessage) {
    String text = aiMessage == null ? null : aiMessage.text();
    if (text == null) {
      log.info("Final answer after {} tool rounds: none", toolRounds);
    } else if (text.length() <= LOGGED_ANSWER_MAX_CHARS) {
      log.info("Final answer after {} tool rounds: {}", toolRounds, text);
    } else {
      log.info("Final answer after {} tool rounds: {} characters", toolRounds, text.length());
    }
  }

  static String withToolBudgetNote(String output, int round, int maxRounds) {
    String note =
        round < maxRounds
            ? String.format(TOOL_BUDGET_NOTE, round, maxRounds, maxRounds - round)
            : TOOL_BUDGET_LAST_ROUND;
    return (output == null ? "" : output) + "\n\n" + note;
  }

  private void recordCost(ChatResponse response, GerritChange change, ChangeSetData changeSetData) {
    if (costTracker != null) {
      costTracker.record(
          response,
          change == null ? null : change.getProjectName(),
          changeSetData == null ? null : changeSetData.getAiUsageSummary());
    }
  }

  private ChatRequest buildChatRequest(List<ChatMessage> messages, ToolChoice toolChoice) {
    ChatRequest.Builder requestBuilder = ChatRequest.builder().messages(messages);

    var parametersBuilder = ChatRequestParameters.builder();
    boolean parametersUsed = false;

    if (onDemandTools != null && !onDemandTools.isEmpty()) {
      parametersBuilder.toolSpecifications(onDemandTools).toolChoice(toolChoice);
      parametersUsed = true;
      log.debug(
          "LangChain on-demand tools exposed: names={}, toolChoice={}, structuredResponse={}",
          getToolNames(),
          toolChoice,
          structuredResponseFormat != null);
    }

    if (structuredResponseFormat != null) {
      if (!parametersUsed) {
        requestBuilder.responseFormat(structuredResponseFormat);
      } else {
        parametersBuilder.responseFormat(structuredResponseFormat);
      }
    }

    if (parametersUsed) {
      requestBuilder.parameters(parametersBuilder.build());
    }

    return requestBuilder.build();
  }

  private ToolChoice getInitialToolChoice() {
    return requireInitialToolUse ? ToolChoice.REQUIRED : ToolChoice.AUTO;
  }

  private String executeToolRequest(
      ToolExecutionRequest request, GerritChange change, ChangeSetData changeSetData) {
    if (request == null || onDemandTools == null || onDemandTools.isEmpty()) {
      log.debug(
          "Skipping LangChain tool request execution because request or configured tools are missing");
      return "";
    }

    String toolName = request.name();
    if (!OnDemandCodeContextTools.FUNCTION_NAMES.contains(toolName)) {
      log.debug("Ignoring unsupported tool request: {}", toolName);
      return "";
    }

    String arguments = request.arguments();
    log.debug(
        "Executing LangChain request id={}, name={}, arguments={}",
        request.id(),
        toolName,
        arguments);
    OnDemandCodeContextTools codeContextTools =
        new OnDemandCodeContextTools(
            config,
            change,
            gitRepoFiles,
            changeSetData.getReviewGroupChangesByPrefix(),
            changeSetData.getCodeContextProjects());
    String output = codeContextTools.execute(toolName, arguments);
    log.debug(
        "Executed LangChain request id={}, name={}, outputLength={}",
        request.id(),
        toolName,
        output != null ? output.length() : 0);
    return output;
  }

  private List<String> getToolNames() {
    if (onDemandTools == null) {
      return List.of();
    }
    return onDemandTools.stream().map(ToolSpecification::name).toList();
  }

  private void logAiMessageToolRequests(String stage, AiMessage aiMessage) {
    if (aiMessage == null) {
      log.debug("LangChain response at {} is null", stage);
      return;
    }
    if (aiMessage.hasToolExecutionRequests()) {
      log.info(
          "LangChain response at {} requested on-demand tools: {}",
          stage,
          aiMessage.toolExecutionRequests());
      return;
    }
    log.debug("LangChain response at {} did not request on-demand tools", stage);
  }
}
