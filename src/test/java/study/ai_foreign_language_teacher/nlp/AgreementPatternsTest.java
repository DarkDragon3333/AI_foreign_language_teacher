package study.ai_foreign_language_teacher.nlp;

import org.junit.jupiter.api.Test;
import study.ai_foreign_language_teacher.domain.TeacherReply.Correction;
import study.ai_foreign_language_teacher.session.CorrectionPolicy;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgreementPatternsTest {

    @Test
    void bikeSentenceKeepsTheLearnersMistakes() {
        String text = "I also like to ride my bike at the my city or in the forest near my house. It's help me tp relax after work";
        List<LtMatch> found = List.of(
                new LtMatch("A_MY", "Collocations", "article + possessive form",
                        "A determiner cannot be combined with a possessive pronoun.",
                        text.indexOf("the my"), 6, "the my", List.of("the", "my")),
                new LtMatch("MORFOLOGIK_RULE_EN_US", "Spelling", "Spelling", "Possible spelling mistake found.",
                        text.lastIndexOf("tp"), 2, "tp", List.of("TP", "to", "up")));
        List<LtMatch> trusted = CorrectionPolicy.confirm(AgreementPatterns.supplement(text, found), null);
        Correction correction = CorrectionPolicy.decide(text, null, trusted);
        assertEquals("I also like to ride my bike in my city or in the forest near my house. It helps me to relax after work",
                correction.corrected());
    }
}
