package dev.langchain4j.model.googleai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Pins the LangChain4j behaviour GeminiLangChainProvider relies on: the thought_signature of a
 * Gemini function call survives the tool round only with returnThinking and sendThinking. Gemini 3
 * rejects the continuation request without it ("Function call is missing a thought_signature").
 */
public class ThoughtSignatureRoundTripTest {

  private static final String SIGNATURE = "c2lnbmF0dXJl";

  @Test
  public void signatureIsSentBackWithTheFunctionCall() {
    assertEquals(SIGNATURE, functionCallSignatureSentBack(true, true));
  }

  @Test
  public void signatureIsDroppedWithoutThinkingFlags() {
    assertNull(functionCallSignatureSentBack(null, false));
  }

  private static String functionCallSignatureSentBack(Boolean returnThinking, boolean sendThinking) {
    GeminiContent.GeminiPart functionCall =
        GeminiContent.GeminiPart.builder()
            .functionCall(new GeminiContent.GeminiPart.GeminiFunctionCall("tree", Map.of()))
            .thoughtSignature(SIGNATURE)
            .build();
    AiMessage aiMessage =
        PartsAndContentsMapper.fromGPartsToAiMessage(List.of(functionCall), false, returnThinking);
    List<ChatMessage> messages =
        List.of(
            UserMessage.from("review"),
            aiMessage,
            ToolExecutionResultMessage.from(aiMessage.toolExecutionRequests().getFirst(), "."));

    List<GeminiContent> contents =
        PartsAndContentsMapper.fromMessageToGContent(messages, null, sendThinking);

    return contents.get(1).parts().stream()
        .filter(part -> part.functionCall() != null)
        .findFirst()
        .orElseThrow()
        .thoughtSignature();
  }
}
