package org.springframework.ai.huaweiai.gallery;

import com.huaweicloud.gallery.dev.sdk.api.callback.StreamCallBack;
import com.huaweicloud.pangu.dev.sdk.client.gallery.GalleryClient;
import com.huaweicloud.pangu.dev.sdk.client.gallery.chat.GalleryChatMessage;
import com.huaweicloud.pangu.dev.sdk.client.gallery.chat.GalleryChatReq;
import com.huaweicloud.pangu.dev.sdk.client.gallery.chat.GalleryChatResp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.ChatClient;
import org.springframework.ai.chat.ChatResponse;
import org.springframework.ai.chat.Generation;
import org.springframework.ai.chat.StreamingChatClient;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.huaweiai.gallery.metadata.HuaweiAiGalleryChatResponseMetadata;
import org.springframework.ai.huaweiai.gallery.util.ApiUtils;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Objects;

public class HuaweiAiGalleryChatClient implements ChatClient, StreamingChatClient {

    private final Logger log = LoggerFactory.getLogger(getClass());

    /**
     * Default options to be used for all chat requests.
     */
    private HuaweiAiGalleryChatOptions defaultOptions;
    /**
     * 华为 盘古大模型 LLM library.
     */
    private final GalleryClient galleryClient;

    private final StreamCallBack streamCallBack;

    private final RetryTemplate retryTemplate;

    public HuaweiAiGalleryChatClient(GalleryClient galleryClient) {
        this(galleryClient, HuaweiAiGalleryChatOptions.builder()
                        .withTemperature(ApiUtils.DEFAULT_TEMPERATURE)
                        .withTopP(ApiUtils.DEFAULT_TOP_P)
                        .build());
    }

    public HuaweiAiGalleryChatClient(GalleryClient galleryClient, HuaweiAiGalleryChatOptions options) {
        this(galleryClient, ApiUtils.DEFAULT_STREAM_CALLBACK, options);
    }

    public HuaweiAiGalleryChatClient(GalleryClient galleryClient, StreamCallBack streamCallBack, HuaweiAiGalleryChatOptions options) {
        this(galleryClient, streamCallBack, options, RetryUtils.DEFAULT_RETRY_TEMPLATE);
    }

    public HuaweiAiGalleryChatClient(GalleryClient galleryClient,
                                     StreamCallBack streamCallBack,
                                     HuaweiAiGalleryChatOptions options,
                                     RetryTemplate retryTemplate) {
        Assert.notNull(galleryClient, "GalleryClient must not be null");
        Assert.notNull(streamCallBack, "StreamCallBack must not be null");
        Assert.notNull(options, "Options must not be null");
        Assert.notNull(retryTemplate, "RetryTemplate must not be null");
        this.galleryClient = galleryClient;
        this.streamCallBack = streamCallBack;
        this.defaultOptions = options;
        this.retryTemplate = retryTemplate;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        Assert.notEmpty(prompt.getInstructions(), "At least one text is required!");
        return retryTemplate.execute(ctx -> {
            // Ask the model.
            GalleryChatResp galleryChatResp;
            // If there is only one instruction, ask the model by prompt.
            if(prompt.getInstructions().size() == 1){
                var inputContent = CollectionUtils.firstElement(prompt.getInstructions()).getContent();
                galleryChatResp = this.galleryClient.createChat(inputContent);
            } else {
                var request = createRequest(prompt, false);
                galleryChatResp = this.galleryClient.createChat(request);
            }
            if (galleryChatResp == null) {
                log.warn("No chat completion returned for prompt: {}", prompt);
                return new ChatResponse(List.of());
            }
            return this.toChatCompletion(galleryChatResp) ;
        });
    }


    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {

        Assert.notEmpty(prompt.getInstructions(), "At least one text is required!");

        return retryTemplate.execute(ctx -> {
            // Ask the model.
            GalleryChatResp galleryChatResp;
            // If there is only one instruction, ask the model by prompt.
            if(prompt.getInstructions().size() == 1){
                var inputContent = CollectionUtils.firstElement(prompt.getInstructions()).getContent();
                galleryChatResp = this.galleryClient.createStreamChat(inputContent, streamCallBack);
            } else {
                var request = createRequest(prompt, true);
                galleryChatResp = this.galleryClient.createStreamChat(request, streamCallBack);
            }
            if (galleryChatResp == null) {
                log.warn("No chat completion returned for prompt: {}", prompt);
                return Flux.empty();
            }
            return Flux.just(toChatCompletion(galleryChatResp)) ;
        });
    }

    private ChatResponse toChatCompletion(GalleryChatResp resp) {

        List<Generation> generations = resp.getChoices()
                .stream()
                .map(choice -> new Generation(choice.getMessage().getContent(), ApiUtils.toMap(resp.getId(), choice))
                        .withGenerationMetadata(ChatGenerationMetadata.from("chat.completion", ApiUtils.extractUsage(resp))))
                .toList();

        return new ChatResponse(generations, HuaweiAiGalleryChatResponseMetadata.from(resp));
    }

    /**
     * Accessible for testing.
     */
    GalleryChatReq createRequest(Prompt prompt, boolean stream) {

        // Build GalleryChatMessage list from the prompt.
        var chatCompletionMessages = prompt.getInstructions()
                .stream()
                .filter(message -> message.getMessageType() == MessageType.USER
                        || message.getMessageType() == MessageType.ASSISTANT
                        || message.getMessageType() == MessageType.SYSTEM)
                .map(m -> GalleryChatMessage.builder().role(ApiUtils.toRole(m).getText()).content(m.getContent()).build())
                .toList();

        // runtime options
        HuaweiAiGalleryChatOptions runtimeOptions = null;
        if (prompt.getOptions() != null) {
            if (prompt.getOptions() instanceof ChatOptions runtimeChatOptions) {
                runtimeOptions = ModelOptionsUtils.copyToTarget(runtimeChatOptions, ChatOptions.class, HuaweiAiGalleryChatOptions.class);
            }
            else {
                throw new IllegalArgumentException("Prompt options are not of type ChatOptions: " + prompt.getOptions().getClass().getSimpleName());
            }
        }

        // Merge runtime options with default options.
        HuaweiAiGalleryChatOptions mergedOptions = ModelOptionsUtils.merge(runtimeOptions, this.defaultOptions, HuaweiAiGalleryChatOptions.class);

        // Build the GalleryChatReq.
        return GalleryChatReq.builder()
                .answerNum(mergedOptions.getAnswerNum())
                .maxTokens(mergedOptions.getMaxTokens())
                .messages(chatCompletionMessages)
                .temperature(Objects.nonNull(mergedOptions.getTemperature()) ? mergedOptions.getTemperature().doubleValue() : null)
                .topP(Objects.nonNull(mergedOptions.getTopP()) ? mergedOptions.getTopP().doubleValue() : null)
                .presencePenalty(mergedOptions.getPresencePenalty())
                .user(mergedOptions.getUser())
                .withPrompt(mergedOptions.getWithPrompt())
                .isStream(stream)
                .build();
    }

}