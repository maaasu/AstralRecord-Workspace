package io.github.maaasu.astralRecord.feature.mail.repository;

import io.github.maaasu.astralRecord.feature.mail.repository.MailRepository.WelcomeTutorialStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WelcomeTutorialStatusTest {
    @Test
    void recognizesAllFourStatusesAndJsonEscapes() {
        for (WelcomeTutorialStatus status : WelcomeTutorialStatus.values()) {
            assertEquals(status, MailRepository.parseWelcomeTutorialStatus(
                " \t{\n \"status\" : \"" + status.name() + "\"\r}\n"));
        }
        assertEquals(WelcomeTutorialStatus.RECEIVED, MailRepository.parseWelcomeTutorialStatus(
            "{\"st\\u0061tus\":\"RECE\\u0049VED\"}"));
    }

    @Test
    void rejectsMalformedAndUnrecognizedResponses() {
        for (String json : List.of(
            "",
            "{}",
            "[]",
            "{\"status\":null}",
            "{\"status\":true}",
            "{\"status\":[\"RECEIVED\"]}",
            "{\"status\":\"received\"}",
            "{\"status\":\"OTHER\"}",
            "{status:\"RECEIVED\"}",
            "{'status':'UNAVAILABLE'}",
            "{\"status\":\"RECEIVED\",}",
            "{\"status\":\"UNKNOWN\",\"status\":\"RECEIVED\"}",
            "{\"status\":\"RECEIVED\"} null",
            "/* comment */{\"status\":\"UNAVAILABLE\"}",
            "{\"status\":\"RECE\\x49VED\"}"
        )) {
            assertEquals(WelcomeTutorialStatus.UNKNOWN, MailRepository.parseWelcomeTutorialStatus(json), json);
        }
    }
}
