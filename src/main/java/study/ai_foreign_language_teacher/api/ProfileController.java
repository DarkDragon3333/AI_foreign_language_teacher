package study.ai_foreign_language_teacher.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import study.ai_foreign_language_teacher.api.Dto.ProfileRequest;
import study.ai_foreign_language_teacher.api.Dto.ProfileView;
import study.ai_foreign_language_teacher.config.TutorProperties;
import study.ai_foreign_language_teacher.domain.Cefr;
import study.ai_foreign_language_teacher.domain.UserProfile;
import study.ai_foreign_language_teacher.memory.ProfileRepository;
import study.ai_foreign_language_teacher.schedule.ScheduleService;

import java.util.List;

@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private final ProfileRepository profiles;
    private final TutorProperties props;

    public ProfileController(ProfileRepository profiles, TutorProperties props) {
        this.profiles = profiles;
        this.props = props;
    }

    @GetMapping
    public ProfileView get() {
        return ProfileView.of(profiles.getOrCreateDefault(), props.ollama().model());
    }

    /** Меняются только переданные поля. */
    @PutMapping
    public ProfileView update(@RequestBody ProfileRequest request) {
        UserProfile current = profiles.getOrCreateDefault();
        String name = request.displayName() == null || request.displayName().isBlank()
                ? current.displayName() : request.displayName().strip();
        if (name.length() > 60) {
            throw new IllegalArgumentException("Name is too long (max 60 characters)");
        }
        int goal = current.dailyGoalMin();
        if (request.dailyGoalMin() != null) {
            goal = request.dailyGoalMin();
            if (goal != 5 && goal != 10 && goal != 15 && goal != 25) {
                throw new IllegalArgumentException("Daily goal must be 5, 10, 15 or 25 minutes");
            }
        }
        List<Integer> weekdays = request.studyWeekdays() == null
                ? current.studyWeekdays() : ScheduleService.normalizeWeekdays(request.studyWeekdays());
        UserProfile updated = new UserProfile(
                current.id(),
                name,
                current.nativeLang(),
                current.targetLang(),
                request.cefr() == null ? current.cefr() : Cefr.parse(request.cefr()),
                request.voiceEnabled() == null ? current.voiceEnabled() : request.voiceEnabled(),
                goal,
                weekdays);
        return ProfileView.of(profiles.save(updated), props.ollama().model());
    }
}
