package study.ai_foreign_language_teacher.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import study.ai_foreign_language_teacher.api.Dto.InsightsView;
import study.ai_foreign_language_teacher.api.Dto.PlanRevisionView;
import study.ai_foreign_language_teacher.domain.UserProfile;
import study.ai_foreign_language_teacher.memory.ErrorRepository;
import study.ai_foreign_language_teacher.memory.ProfileRepository;
import study.ai_foreign_language_teacher.memory.SessionRepository;
import study.ai_foreign_language_teacher.schedule.InsightsService;
import study.ai_foreign_language_teacher.schedule.ScheduleService;

@RestController
@RequestMapping("/api/insights")
public class InsightsController {

    private final ProfileRepository profiles;
    private final SessionRepository sessions;
    private final ErrorRepository errors;
    private final ScheduleService schedule;

    public InsightsController(ProfileRepository profiles, SessionRepository sessions, ErrorRepository errors,
                              ScheduleService schedule) {
        this.profiles = profiles;
        this.sessions = sessions;
        this.errors = errors;
        this.schedule = schedule;
    }

    @GetMapping
    public InsightsView recent(@RequestParam(defaultValue = "5") int limit) {
        UserProfile profile = profiles.getOrCreateDefault();
        int n = Math.min(20, Math.max(1, limit));
        return InsightsView.of(InsightsService.collect(sessions, errors, profile.id(), n));
    }

    @PostMapping("/plan")
    public PlanRevisionView revisePlan() {
        return PlanRevisionView.of(schedule.revisePlan());
    }
}