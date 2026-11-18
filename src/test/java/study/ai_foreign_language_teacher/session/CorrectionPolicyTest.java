package study.ai_foreign_language_teacher.session;

import org.junit.jupiter.api.Test;
import study.ai_foreign_language_teacher.domain.TeacherReply.Correction;
import study.ai_foreign_language_teacher.domain.TeacherReply.ErrorItem;
import study.ai_foreign_language_teacher.nlp.LtMatch;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class CorrectionPolicyTest {

    private static LtMatch lt(String text, String bad, String good) {
        return new LtMatch("RULE_" + bad.toUpperCase().replace(' ', '_'), "Grammar", "Some rule", "Wrong form.",
                text.indexOf(bad), bad.length(), bad, List.of(good));
    }

    @Test
    void truncatedSentenceIsNotACorrection() {
        // реальный случай: модель «исправила» правильную фразу, выбросив второе предложение
        String text = "Hi! I want to ride my bike on vacation. My favorite place for traveling is Greece";
        Correction model = new Correction(text, "I want to ride my bike on vacation.", List.of());
        assertNull(CorrectionPolicy.decide(text, model, List.of()));
    }

    @Test
    void rewriteWithoutPinpointedErrorIsRejected() {
        String text = "I like to travel by train";
        Correction model = new Correction(text, "I enjoy travelling by train.",
                List.of(new ErrorItem("I like to travel by train", "Style", "Sounds better")));
        assertNull(CorrectionPolicy.decide(text, model, List.of()));
    }

    @Test
    void pinpointedModelErrorIsAccepted() {
        String text = "I go to Turkey last summer";
        Correction model = new Correction(text, "I went to Turkey last summer.",
                List.of(new ErrorItem("go", "Past Simple", "go -> went"), new ErrorItem("holiday", "Vocabulary", "not in text")));
        Correction c = CorrectionPolicy.decide(text, model, List.of());
        assertNotNull(c);
        assertEquals("I went to Turkey last summer.", c.corrected());
        assertEquals(1, c.errors().size());
        assertEquals("Past Simple", c.errors().get(0).rule());
    }

    @Test
    void errorWhoseSpanSurvivesInCorrectedTextIsRejected() {
        String text = "I have a big dog";
        Correction model = new Correction(text, "I have a large dog.", List.of(new ErrorItem("have", "Verb", "?")));
        assertNull(CorrectionPolicy.decide(text, model, List.of()));
    }

    @Test
    void checkerFindingsAreShownEvenIfModelIgnoredThem() {
        String text = "I ate a apple and read informations";
        Correction model = new Correction(text, text, List.of());
        Correction c = CorrectionPolicy.decide(text, model, List.of(lt(text, "a apple", "an apple"), lt(text, "informations", "information")));
        assertNotNull(c);
        assertEquals("I ate an apple and read information", c.corrected());
        assertEquals(2, c.errors().size());
        assertEquals("a apple", c.errors().get(0).span());
    }

    @Test
    void checkerAndModelErrorsAreMergedWithoutDuplicates() {
        String text = "Yesterday I go to shop and buy a apple";
        Correction model = new Correction(text, "Yesterday I went to the shop and bought an apple.",
                List.of(new ErrorItem("go", "Past Simple", "went"), new ErrorItem("a apple", "Articles", "an")));
        Correction c = CorrectionPolicy.decide(text, model, List.of(lt(text, "a apple", "an apple")));
        assertEquals("Yesterday I went to the shop and bought an apple.", c.corrected());
        assertEquals(2, c.errors().size());
        assertEquals("a apple", c.errors().get(0).span());
        assertEquals("go", c.errors().get(1).span());
    }

    @Test
    void sameTextIgnoresCaseAndOuterPunctuation() {
        Correction model = new Correction("i am fine", "I am fine.", List.of(new ErrorItem("i", "Capital letter", "I")));
        assertNull(CorrectionPolicy.decide("i am fine", model, List.of()));
    }

    private static LtMatch typo(String text, String bad, String... suggestions) {
        return new LtMatch("MORFOLOGIK_RULE_EN_US", "Spelling", "Spelling", "Possible spelling mistake found.",
                text.indexOf(bad), bad.length(), bad, List.of(suggestions));
    }

    @Test
    void spellingOfNamesIsNotCorrectedUnlessTheModelAgrees() {
        // реальный случай: LanguageTool превратил «lada largus» в «Lady larges»
        String text = "Hi! My favotite car is lada largus. It's cheep and not small car. I like it";
        Correction model = new Correction(text, "Hi! My favorite car is Lada Largus. It's cheap and not a small car. I like it", List.of());
        List<LtMatch> found = List.of(typo(text, "favotite", "favorite"), typo(text, "lada", "Lady", "lad"),
                typo(text, "largus", "larges"), lt(text, "cheep", "cheap"));
        List<LtMatch> trusted = CorrectionPolicy.confirm(found, model);
        assertEquals(2, trusted.size());
        Correction c = CorrectionPolicy.decide(text, model, trusted);
        assertEquals("Hi! My favorite car is lada largus. It's cheap and not small car. I like it", c.corrected());
        assertEquals("favotite", c.errors().get(0).span());
        assertEquals("cheep", c.errors().get(1).span());
    }

    @Test
    void unconfirmedTyposAreDroppedButGrammarStays() {
        String text = "I ate a apple with lada";
        List<LtMatch> trusted = CorrectionPolicy.confirm(List.of(lt(text, "a apple", "an apple"), typo(text, "lada", "Lady")), null);
        assertEquals(1, trusted.size());
        assertEquals("a apple", trusted.get(0).span());
    }

    @Test
    void theMyKeepsThePossessive() {
        String text = "I ride at the my city";
        Correction c = CorrectionPolicy.decide(text, null,
                List.of(new LtMatch("A_MY", "Collocations", "article + possessive form",
                        "Did you mean the or my?", text.indexOf("the my"), 6, "the my", List.of("the", "my"))));
        assertEquals("I ride at my city", c.corrected());
    }
}
