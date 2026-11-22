package study.ai_foreign_language_teacher.api;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import study.ai_foreign_language_teacher.api.Dto.ApplyLearningPlanRequest;
import study.ai_foreign_language_teacher.api.Dto.CompleteActivityRequest;
import study.ai_foreign_language_teacher.api.Dto.ScheduleSlotPatch;
import study.ai_foreign_language_teacher.api.Dto.ScheduleMonthView;
import study.ai_foreign_language_teacher.api.Dto.ScheduleWeekView;
import study.ai_foreign_language_teacher.api.Dto.StudyWeekdaysRequest;
import study.ai_foreign_language_teacher.domain.ActivityType;
import study.ai_foreign_language_teacher.domain.SlotStatus;
import study.ai_foreign_language_teacher.session.LessonService;
import study.ai_foreign_language_teacher.schedule.ScheduleService;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/schedule")
public class ScheduleController {

    private final ScheduleService schedule;
    private final LessonService lessons;

    public ScheduleController(ScheduleService schedule, LessonService lessons) {
        this.schedule = schedule;
        this.lessons = lessons;
    }

    @GetMapping("/week")
    public ScheduleWeekView week() {
        return ScheduleWeekView.of(schedule.week());
    }

    @GetMapping("/month")
    public ScheduleMonthView month(@RequestParam int year, @RequestParam int month) {
        return ScheduleMonthView.of(schedule.month(year, month));
    }

    @PostMapping("/refresh")
    public ScheduleWeekView refresh() {
        return ScheduleWeekView.of(schedule.week());
    }

    @PatchMapping("/weekdays")
    public ScheduleWeekView weekdays(@RequestBody StudyWeekdaysRequest body) {
        return ScheduleWeekView.of(schedule.updateWeekdays(body == null ? null : body.days()));
    }

    @PatchMapping("/slots")
    public ScheduleService.SlotView patchSlot(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestBody ScheduleSlotPatch body) {
        ActivityType type = body == null || body.activityType() == null ? null : ActivityType.parse(body.activityType());
        SlotStatus status = body == null || body.status() == null ? null : SlotStatus.parse(body.status());
        return schedule.patchSlot(date, type, status);
    }

    @PostMapping("/apply-plan")
    public ScheduleMonthView applyPlan(@RequestBody ApplyLearningPlanRequest body) {
        if (body == null) {
            throw new IllegalArgumentException("plan body is required");
        }
        lessons.applyPlacementPlan(body.toPlan());
        var now = java.time.LocalDate.now();
        return ScheduleMonthView.of(schedule.month(now.getYear(), now.getMonthValue()));
    }

    @PostMapping("/complete")
    public ResponseEntity<ScheduleService.SlotView> complete(@RequestBody(required = false) CompleteActivityRequest body) {
        if (body == null || body.activityType() == null || body.activityType().isBlank()) {
            throw new IllegalArgumentException("activityType is required");
        }
        ScheduleService.SlotView view = schedule.completeToday(ActivityType.parse(body.activityType()));
        if (view == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(view);
    }
}