package com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.provider.gemini;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.model.LangChainProvider;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.provider.FallbackTokenCountEstimator;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.langchain.provider.ILangChainProvider;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.googleai.GeminiThinkingConfig;
import java.lang.reflect.Field;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

public class GeminiLangChainProviderTest {

  private final GeminiLangChainProvider provider = new GeminiLangChainProvider();

  @Before
  public void resetEstimatorCache() throws Exception {
    Field field = GeminiLangChainProvider.class.getDeclaredField("estimatorAvailable");
    field.setAccessible(true);
    field.set(null, null);
    dev.langchain4j.model.googleai.GoogleAiTokenCountEstimator.Builder.reset();
  }

  @Test
  public void createTokenEstimatorUsesDefaultGeminiModel() {
    Configuration config = Mockito.mock(Configuration.class);
    // Deliberately return a different provider model to prove the estimator ignores config and
    // uses the OpenAI default model constant instead.
    when(config.getAiModel()).thenReturn("gpt-4.1");
    when(config.getAiToken()).thenReturn("dummy-token");

    Optional<TokenCountEstimator> estimator = provider.createTokenEstimator(config);

    assertTrue(estimator.isPresent());
    assertTrue(estimator.get() instanceof FallbackTokenCountEstimator);
    assertEquals(
        Configuration.DEFAULT_GEMINI_ESTIMATOR_MODEL,
        dev.langchain4j.model.googleai.GoogleAiTokenCountEstimator.Builder.getLastModelName());
  }

  @Test
  public void setsLangChainMaxRetriesToOne() throws Exception {
    Configuration config = Mockito.mock(Configuration.class);
    when(config.getAiDomain()).thenReturn(Configuration.GEMINI_DOMAIN);
    when(config.getAiToken()).thenReturn("dummy-token");
    when(config.getAiModel()).thenReturn("gemini-2.5-flash");
    when(config.getAiConnectionTimeout()).thenReturn(180);

    LangChainProvider langChainProvider = provider.buildChatModel(config, 0.0);

    assertEquals(ILangChainProvider.LANGCHAIN_MAX_RETRIES, getMaximumRetries(langChainProvider));
  }

  @Test
  public void keepsThoughtSignaturesForToolRounds() throws Exception {
    Configuration config = Mockito.mock(Configuration.class);
    when(config.getAiDomain()).thenReturn(Configuration.GEMINI_DOMAIN);
    when(config.getAiToken()).thenReturn("dummy-token");
    when(config.getAiModel()).thenReturn("gemini-3.8-flash");
    when(config.getAiConnectionTimeout()).thenReturn(180);

    LangChainProvider langChainProvider = provider.buildChatModel(config, 0.0);

    assertEquals(Boolean.TRUE, getModelField(langChainProvider, "returnThinking"));
    assertEquals(Boolean.TRUE, getModelField(langChainProvider, "sendThinking"));
  }

  @Test
  public void buildsALowThinkingModelForFinalAnswersOfGemini3() throws Exception {
    Configuration config = Mockito.mock(Configuration.class);
    when(config.getAiDomain()).thenReturn(Configuration.GEMINI_DOMAIN);
    when(config.getAiToken()).thenReturn("dummy-token");
    when(config.getAiModel()).thenReturn("gemini-3.8-flash");
    when(config.getAiConnectionTimeout()).thenReturn(180);

    LangChainProvider langChainProvider = provider.buildChatModel(config, 1.0);

    ChatModel finalAnswerModel = langChainProvider.getFinalAnswerModel();
    assertNotNull(finalAnswerModel);
    GeminiThinkingConfig thinking =
        (GeminiThinkingConfig) getField(finalAnswerModel, "thinkingConfig");
    assertTrue("low".equalsIgnoreCase(thinking.thinkingLevel()));
    assertEquals(Boolean.TRUE, getField(finalAnswerModel, "sendThinking"));
    assertNull(getField(langChainProvider.getModel(), "thinkingConfig"));
  }

  @Test
  public void hasNoFinalAnswerModelBeforeGemini3() {
    Configuration config = Mockito.mock(Configuration.class);
    when(config.getAiDomain()).thenReturn(Configuration.GEMINI_DOMAIN);
    when(config.getAiToken()).thenReturn("dummy-token");
    when(config.getAiModel()).thenReturn("gemini-2.5-flash");
    when(config.getAiConnectionTimeout()).thenReturn(180);

    assertNull(provider.buildChatModel(config, 1.0).getFinalAnswerModel());
  }

  private static Object getField(ChatModel model, String name) throws Exception {
    Field field = model.getClass().getSuperclass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(model);
  }

  private static Object getModelField(LangChainProvider langChainProvider, String name)
      throws Exception {
    // Declared on the package-private BaseGeminiChatModel.
    Field field = langChainProvider.getModel().getClass().getSuperclass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(langChainProvider.getModel());
  }

  private static int getMaximumRetries(LangChainProvider langChainProvider) throws Exception {
    Field field = langChainProvider.getModel().getClass().getDeclaredField("maximumRetries");
    field.setAccessible(true);
    return (int) field.get(langChainProvider.getModel());
  }
}
