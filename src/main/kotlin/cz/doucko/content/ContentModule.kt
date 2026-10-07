package cz.doucko.content

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Package metadata for `cz.doucko.content` (store spec content-authoring, FR-1 to FR-13, FR-55 to FR-57,
 * NFR-17): PDF sources, tasks with answer parts, hints, translations and images, the task list,
 * preview, answer tester and bulk publish, and missions with their publishing and delete rules, all
 * under `/api/admin/`. Uploaded files go through `FileStoragePort` to a directory outside every
 * repository. Public API for other modules: [ServedContent] (what the child may be served, FR-12),
 * [ContentCatalog] (topics and a task's current parts for the admin statistics and re-evaluation, FR-40,
 * FR-41) and the [ContentUsage] port that practice implements (attempts, problem reports and opened hints
 * block deletes, FR-56).
 */
@PackageInfo
@ApplicationModule(displayName = "Content authoring")
class ContentModule
