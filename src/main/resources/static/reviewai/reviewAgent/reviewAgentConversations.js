(function(global) {
  const reviewAi = global.ReviewAi;

  reviewAi.agentConversationMethods = {
    async listChatConversations(change) {
      if (!(await this._canAiReviewChange(change))) {
        return [];
      }

      return this._listStoredConversations(change);
    },

    async getChatConversation(change, conversationId) {
      if (!conversationId || !(await this._canAiReviewChange(change))) {
        return [];
      }

      const storedConversation = await this._getStoredConversation(change, conversationId);
      if (storedConversation) {
        const entries = await this._fetchEntries(change);
        if (Array.isArray(storedConversation.turns)) {
          storedConversation.turns = await Promise.all(
            storedConversation.turns.map((turn, index) =>
              this._resolvePendingTurn(change, conversationId, turn, index, entries)
            )
          );
        }
        reviewAi.agentUtils.linkConversationReplyHeaders(storedConversation, entries);
        return Array.isArray(storedConversation.turns) ? storedConversation.turns : [];
      }

      return [];
    },
  };
})(window);
