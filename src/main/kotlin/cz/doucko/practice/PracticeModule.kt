package cz.doucko.practice

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Package metadata for `cz.doucko.practice` (store specs practice-flow, gamification and progress-stats;
 * FR-22, FR-24..FR-42, FR-44): HQ, mission briefing, the task screen, attempts and the reveal, hints,
 * problem reports and save/resume under `/api/child/`, login-free; XP and ranks; and the admin statistics,
 * flag queue, re-evaluation and dismiss under `/api/admin/stats` and `/api/admin/reports` (admin chain).
 * Reads content only through `cz.doucko.content.ServedContent` and `cz.doucko.content.ContentCatalog`, grades with `cz.doucko.checking.AnswerChecker`, reads the profile
 * through `cz.doucko.profile.ChildProfile`, and implements the content port `ContentUsage`. Feedback comes
 * only through the `TutorPort` seam. Nothing is exposed to other modules.
 */
@PackageInfo
@ApplicationModule(displayName = "Practice flow")
class PracticeModule
