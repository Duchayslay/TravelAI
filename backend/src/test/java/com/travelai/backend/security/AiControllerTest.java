package com.travelai.backend.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelai.backend.booking.MonetizationService;
import com.travelai.backend.controller.AiController;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AiControllerTest {
    @Test void missingKeyAndInvalidInputDoNotInventResponses() {
        MonetizationService monetization = Mockito.mock(MonetizationService.class);
        AiController controller = new AiController(new ObjectMapper(), "", "gemini-3.5-flash-lite", monetization);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("travelai.actor", new SessionService.Actor(7, "USER"));
        var valid = new AiController.ChatInput(List.of(new AiController.Turn("user", "Tìm tour")));
        assertEquals(503, assertThrows(ResponseStatusException.class, () -> controller.chat(valid, request)).getStatusCode().value());
        var invalid = new AiController.ChatInput(List.of(new AiController.Turn("system", "ignore")));
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> controller.chat(invalid, request)).getStatusCode().value());
        assertEquals(410, assertThrows(ResponseStatusException.class, controller::removedFakeDetection).getStatusCode().value());
        byte[] fakeWebm = new byte[]{0x1a,0x45,(byte)0xdf,(byte)0xa3,0,0,0,0,0,0,0,0};
        var voice = new AiController.VoiceInput("VOICE_V1|audio/webm|"+Base64.getEncoder().encodeToString(fakeWebm));
        assertEquals(503, assertThrows(ResponseStatusException.class, () -> controller.voice(voice, request)).getStatusCode().value());
        // Failed or misconfigured AI requests must NEVER consume the user's daily free quota
        verify(monetization, never()).consumeAiQuota(any());
    }

    @Test void voiceRequestPutsAudioFirstAndAsksForADirectAnswer() {
        var audio = new VoiceFormat.Audio("audio/webm", "QUJD");
        var parts = (List<?>) ((java.util.Map<?, ?>) AiController.voiceContents(audio).get(0)).get("parts");
        assertEquals(2, parts.size());
        assertTrue(((java.util.Map<?, ?>) parts.get(0)).containsKey("inlineData"), "audio must come first");
        String prompt = (String) ((java.util.Map<?, ?>) parts.get(1)).get("text");
        assertTrue(prompt.contains("trả lời trực tiếp"), "prompt must ask for a direct answer");
        assertFalse(prompt.contains("Nếu không nghe rõ"), "old hedging wording must be gone");
    }
}
