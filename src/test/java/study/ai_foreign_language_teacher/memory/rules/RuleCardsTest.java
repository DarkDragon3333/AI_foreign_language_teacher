package study.ai_foreign_language_teacher.memory.rules;

import org.junit.jupiter.api.Test;
import study.ai_foreign_language_teacher.domain.Cefr;
import study.ai_foreign_language_teacher.domain.FsrsState;
import study.ai_foreign_language_teacher.domain.LessonMode;
import study.ai_foreign_language_teacher.domain.LessonSession;
import study.ai_foreign_language_teacher.domain.ReviewRating;
import study.ai_foreign_language_teacher.domain.RuleCard;
import study.ai_foreign_language_teacher.domain.UserProfile;
import study.ai_foreign_language_teacher.llm.PromptBuilder;
import study.ai_foreign_language_teacher.memory.ProfileRepository;
import study.ai_foreign_language_teacher.memory.inmemory.MemoryReviewRepository;
import study.ai_foreign_language_teacher.session.ScenarioCatalog;
import study.ai_foreign_language_teacher.session.ScenarioService;
import study.ai_foreign_language_teacher.nlp.LtMatch;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleCardsTest {

    private final CardCatalog catalog = new CardCatalog(JsonMapper.builder().build());
    private final RuleRetriever retriever = new RuleRetriever(catalog, text -> null);

    @Test
    void catalogHasACheckedSet() {
        assertTrue(catalog.all().size() >= 150, "карточек: " + catalog.all().size());
    }

    @Test
    void languageToolRuleSelectsTheCard() {
        LtMatch match = new LtMatch("HE_VERB_AGR", "Grammar", "Agreement", "He go is wrong", 0, 6, "He go", List.of("goes"));
        List<RuleCard> cards = retriever.retrieve(Cefr.A2, LessonMode.CONVERSATION, null, "He go to school", List.of(match), false);
        assertFalse(cards.isEmpty());
        assertEquals("present_simple_s", cards.get(0).slug());
    }

    @Test
    void checkerProseDoesNotPullAnUnrelatedCard() {
        String text = "I also like to ride my bike at the my city";
        LtMatch match = new LtMatch("A_MY", "Collocations", "article + possessive form",
                "A determiner cannot be combined with a possessive pronoun.",
                text.indexOf("the my"), 6, "the my", List.of("the", "my"));
        List<RuleCard> cards = retriever.retrieve(Cefr.A2, LessonMode.CONVERSATION, null, text, List.of(match), false);
        assertEquals("article_and_possessive", cards.get(0).slug());
        assertTrue(cards.stream().noneMatch(c -> c.slug().equals("possessive_s")));
    }

    @Test
    void correctDuringDoesNotPullTheDuringCard() {
        String text = "I like ride my bike during the week. I work out at my gym on the weekend";
        LtMatch match = new LtMatch("OTHER", "Grammar", "Something else", "Unrelated.",
                text.indexOf("like"), 4, "like", List.of("to like"));
        List<RuleCard> cards = retriever.retrieve(Cefr.A2, LessonMode.CONVERSATION, null, text, List.of(match), false);
        assertTrue(cards.stream().noneMatch(c -> c.slug().equals("during")));
    }

    @Test
    void russianQuestionFindsHaveBeen() {
        List<RuleCard> cards = retriever.retrieve(Cefr.B1, LessonMode.CONVERSATION, null,
                "когда писать have been", null, false);
        assertFalse(cards.isEmpty());
        assertEquals("present_perfect_continuous", cards.get(0).slug());
    }

    @Test
    void correctChatterGetsNoCard() {
        List<RuleCard> cards = retriever.retrieve(Cefr.B1, LessonMode.CONVERSATION, "travel",
                "I visited Rome yesterday", List.of(), false);
        assertTrue(cards.isEmpty());
    }

    @Test
    void promptForbidsInventedRules() {
        RuleCard card = catalog.find("past_simple_ed").orElseThrow();
        UserProfile profile = new UserProfile(1, "A", "ru", "en", Cefr.A2, false, 10, UserProfile.DEFAULT_STUDY_WEEKDAYS, null);
        LessonSession session = new LessonSession(1, 1, LessonMode.CONVERSATION, null, Cefr.A2, 0, Instant.now(), null, null, null);
        var mapper = JsonMapper.builder().build();
        PromptBuilder prompts = new PromptBuilder(mapper, new ScenarioService(new ScenarioCatalog(mapper)),
                org.mockito.Mockito.mock(study.ai_foreign_language_teacher.session.PlacementLessonService.class));
        String system = prompts.build(profile, session, List.of(), false, List.of(), List.of(), List.of(), List.of(card)).get(0).content();
        assertTrue(system.contains(card.rule()));
        assertTrue(system.contains("do not invent"));
        assertTrue(system.contains(card.exampleBad()));
    }

    @Test
    void firstRatingsMatchTheUsualGaps() {
        Instant now = Instant.parse("2026-03-01T10:00:00Z");
        FsrsState fresh = FsrsState.fresh(1, "past_simple_ed", now);
        assertEquals(0, FsrsScheduler.review(fresh, ReviewRating.AGAIN, now).intervalDays());
        assertEquals(1, FsrsScheduler.review(fresh, ReviewRating.HARD, now).intervalDays());
        assertEquals(3, FsrsScheduler.review(fresh, ReviewRating.GOOD, now).intervalDays());
        assertEquals(16, FsrsScheduler.review(fresh, ReviewRating.EASY, now).intervalDays());
    }

    @Test
    void successLengthensTheGapAndALapseShortensIt() {
        Instant now = Instant.parse("2026-03-01T10:00:00Z");
        FsrsScheduler.Outcome first = FsrsScheduler.review(FsrsState.fresh(1, "for_since", now), ReviewRating.GOOD, now);
        Instant later = now.plus(Duration.ofDays(3));
        FsrsScheduler.Outcome again = FsrsScheduler.review(first.state(), ReviewRating.GOOD, later);
        assertTrue(again.state().stability() > first.state().stability());
        FsrsScheduler.Outcome lapse = FsrsScheduler.review(first.state(), ReviewRating.AGAIN, later);
        assertTrue(lapse.state().stability() < first.state().stability());
        assertEquals("relearning", lapse.state().state());
    }

    @Test
    void aMistakeEntersTodaysQueueOnce() {
        MemoryReviewRepository repo = new MemoryReviewRepository();
        ProfileRepository profiles = new ProfileRepository() {
            @Override
            public UserProfile getOrCreateDefault() {
                return new UserProfile(7, "A", "ru", "en", Cefr.B1, false, 10, UserProfile.DEFAULT_STUDY_WEEKDAYS, null);
            }

            @Override
            public UserProfile save(UserProfile profile) {
                return profile;
            }
        };
        ReviewService reviews = new ReviewService(repo, profiles, catalog);
        reviews.noteMistake(7, "for_since");
        reviews.noteMistake(7, "for_since");
        assertEquals(1, reviews.queue().due());
        assertEquals(0, repo.find(7, "for_since").orElseThrow().reps());
    }
}
