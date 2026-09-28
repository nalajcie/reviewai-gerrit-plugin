(function(global) {
  const reviewAi = global.ReviewAi;
  const agentUtils = reviewAi.agentUtils;

  class ReviewAiCodeReviewProvider {
    constructor(plugin, pluginName) {
      this.plugin = plugin;
      this.pluginName = pluginName;
      this.conversationTurns = new reviewAi.ReviewAgentConversationTurns(this);
      this.supports_add_context = false;
      this.supports_history = true;
      this.supports_more_menu = false;
      this.supports_this_change = true;
    }

    chat(req, listener) {
      this._chatAsync(req, listener);
    }

    async _chatAsync(req, listener) {
      try {
        const change = req.change;
        if (!agentUtils.getChangeNumber(change)) {
          throw new Error('ReviewAI needs a loaded Gerrit change to answer.');
        }

        const modelInfo = await this._fetchModelInfo(change);
        if (!agentUtils.canAiReview(modelInfo)) {
          throw new Error('ReviewAI is not allowed for this change.');
        }

        const prompt = this._normalizePrompt(req);
        if (!prompt) {
          throw new Error('Enter a message for ReviewAI.');
        }

        const baselineEntries = await this._fetchEntries(change);
        const baselineKeys = new Set(baselineEntries.map(agentUtils.entryKey));
        const conversationId = this._getRequestConversationId(req, change);
        const requestId = this._newRequestId(change);

        const sendResult = await this._sendMessage(
          change,
          prompt,
          this._getRequestModelId(req),
          requestId
        );
        const directResponse =
          sendResult && (sendResult.response_text || sendResult.responseText);
        const sentRequestId =
          (sendResult && (sendResult.request_id || sendResult.requestId)) || requestId;
        const shouldWaitForAssistantReply =
          !agentUtils.isDirectResponsePrompt(prompt) &&
          !(
            sendResult &&
            (sendResult.wait_for_assistant_reply === false ||
              sendResult.waitForAssistantReply === false)
          );
        // A note from the running review (e.g. only this change of the group is reviewed) is shown
        // at once, as the first part of the answer.
        let notice = null;
        const assistantReply = shouldWaitForAssistantReply
          ? await this._waitForAssistantReply(change, sentRequestId, baselineKeys, {
              excludeDynamicConfiguration: Boolean(directResponse),
              onNotice: text => {
                notice = text;
                listener.emitResponse(agentUtils.buildChatResponse(`${text}\n\n`, 0));
              },
            })
          : null;
        // Still running when the panel stops waiting: keep what is needed to fill in the answer
        // when the conversation is opened again.
        const pending =
          shouldWaitForAssistantReply && assistantReply === null
            ? {
                request_id: sentRequestId,
                since_updated: agentUtils.latestUpdated(baselineEntries),
                direct_response: directResponse || '',
              }
            : null;
        const responseText = agentUtils.withoutNotice(
          !shouldWaitForAssistantReply
            ? directResponse
            : agentUtils.joinAgentResponses(
                directResponse,
                pending ? agentUtils.pendingResponseText : assistantReply
              ),
          notice
        );
        await this.conversationTurns.storeConversationTurn(
          change,
          req,
          conversationId,
          prompt,
          agentUtils.joinAgentResponses(notice, responseText),
          pending
        );
        listener.emitResponse(agentUtils.buildChatResponse(responseText, notice ? 1 : 0));
        listener.done();
      } catch (error) {
        listener.emitResponse(
          agentUtils.buildChatResponse(error instanceof Error ? error.message : String(error))
        );
        listener.done();
      }
    }

    _normalizePrompt(req) {
      const explicitPrompt = (req && req.prompt ? req.prompt : '').trim();
      const actionPrompt =
        req && req.action && req.action.initial_user_prompt
          ? req.action.initial_user_prompt.trim()
          : '';
      const prompt = explicitPrompt || actionPrompt;

      if (!prompt || agentUtils.isCommandPrompt(prompt)) {
        return prompt;
      }
      return `/message ${prompt}`;
    }

    _getRequestConversationId(req, change) {
      return (
        (req && (req.conversation_id || req.conversationId)) ||
        this.conversationTurns.conversationId(change)
      );
    }

    async _waitForAssistantReply(change, requestId, baselineKeys, options) {
      const config = options || {};
      const deadline = Date.now() + agentUtils.agentConfig.responseTimeoutMs;
      const pollIntervalMs =
        agentUtils.agentConfig.responsePollIntervalMs || reviewAi.config.pollIntervalMs;
      let shownNotice = null;

      while (Date.now() < deadline) {
        await agentUtils.sleep(pollIntervalMs);
        let status = null;
        try {
          status = await this._fetchMessageStatus(change, requestId);
        } catch {
          const historyResponse = await this._getNewAssistantHistoryResponse(
            change,
            baselineKeys,
            config
          );
          if (historyResponse) {
            return historyResponse;
          }
          continue;
        }
        const state = status && status.status;
        const statusResponse =
          status && (status.response_text || status.responseText);
        const notice = status && status.notice;
        if (notice && notice !== shownNotice && config.onNotice) {
          shownNotice = notice;
          config.onNotice(notice);
        }
        if (state === 'failed') {
          return statusResponse || 'ReviewAI request failed.';
        }
        if (state !== 'completed') {
          continue;
        }

        const historyResponse = await this._getNewAssistantHistoryResponse(
          change,
          baselineKeys,
          config
        );
        if (historyResponse) {
          return agentUtils.mergePanelResponseWithHistory(historyResponse, statusResponse);
        }
        return statusResponse || 'ReviewAI completed the request without a visible update.';
      }

      return null;
    }

    /**
     * Fills in the answer of a turn stored while its request was still running, once the request
     * has finished. Returns the updated turn, or the turn unchanged while it is still running.
     */
    async _resolvePendingTurn(change, conversationId, turn, turnIndex, entries) {
      const pending = turn && turn[agentUtils.pendingTurnKey];
      if (!pending) {
        return turn;
      }
      let status = null;
      try {
        status = await this._fetchMessageStatus(change, pending.request_id);
      } catch {
        status = null;
      }
      const state = status && status.status;
      const statusResponse = status && (status.response_text || status.responseText);
      const newEntries = agentUtils.assistantEntriesSince(
        entries,
        pending.since_updated,
        Boolean(pending.direct_response)
      );
      if (state !== 'completed' && state !== 'failed' && !(!state && newEntries.length)) {
        return turn;
      }
      const historyResponse = agentUtils.formatAgentEntries(newEntries);
      let answer;
      if (state === 'failed') {
        answer = statusResponse || historyResponse || 'ReviewAI request failed.';
      } else if (historyResponse) {
        answer = agentUtils.mergePanelResponseWithHistory(historyResponse, statusResponse);
      } else {
        answer = statusResponse || 'ReviewAI completed the request without a visible update.';
      }
      const resolvedTurn = {
        ...turn,
        response: agentUtils.buildChatResponse(
          agentUtils.normalizeResponseEntrySeparators(
            agentUtils.joinAgentResponses(pending.direct_response, answer)
          )
        ),
      };
      delete resolvedTurn[agentUtils.pendingTurnKey];
      try {
        await this._appendStoredConversationTurn(change, {
          conversationId,
          conversation_id: conversationId,
          turnIndex,
          turn_index: turnIndex,
          turn: resolvedTurn,
        });
      } catch {
        // Shown now; stored again on the next open.
      }
      return resolvedTurn;
    }

    async _getNewAssistantHistoryResponse(change, baselineKeys, config) {
      const latestNewAssistantEntries = (await this._fetchEntries(change)).filter(
        entry =>
          agentUtils.isAssistantEntry(entry) &&
          !baselineKeys.has(agentUtils.entryKey(entry)) &&
          !(
            config &&
            config.excludeDynamicConfiguration &&
            agentUtils.isDynamicConfigurationEntry(entry)
          )
      );
      return agentUtils.formatAgentEntries(latestNewAssistantEntries);
    }

    async _fetchEntries(change) {
      const result = await this._fetchHistory(change);
      return reviewAi.entries.fromResult(result);
    }

    _fetchHistory(change) {
      return reviewAi.api.createFetchHistory(this.plugin, this.pluginName)(change);
    }

    _sendMessage(change, message, modelId, requestId) {
      return reviewAi.api.createSendMessage(this.plugin, this.pluginName)(
        change,
        message,
        modelId,
        true,
        requestId
      );
    }

    _fetchMessageStatus(change, requestId) {
      return reviewAi.api.createFetchMessageStatus(this.plugin, this.pluginName)(
        change,
        requestId
      );
    }

    _newRequestId(change) {
      const changeNumber = agentUtils.getChangeNumber(change) || 'change';
      return `${changeNumber}-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
    }

    _getRequestModelId(req) {
      return (
        (req && (req.model_name || req.modelName || req.model_id || req.modelId)) ||
        (req && typeof req.model === 'string' && req.model) ||
        (req && req.model && (req.model.model_id || req.model.modelId)) ||
        ''
      );
    }
  }

  Object.assign(
    ReviewAiCodeReviewProvider.prototype,
    reviewAi.agentModelMethods,
    reviewAi.agentConversationStoreMethods,
    reviewAi.agentConversationMethods
  );

  reviewAi.agent = {
    _registered: false,

    register(plugin, pluginName) {
      if (this._registered || !plugin.aiCodeReview) {
        return false;
      }

      const aiCodeReviewApi = plugin.aiCodeReview();
      if (!aiCodeReviewApi || !aiCodeReviewApi.register) {
        return false;
      }

      aiCodeReviewApi.register(new ReviewAiCodeReviewProvider(plugin, pluginName));
      this._registered = true;
      return true;
    },
  };
})(window);
