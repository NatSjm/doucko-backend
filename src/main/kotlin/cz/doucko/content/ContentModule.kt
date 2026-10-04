package cz.doucko.content

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Package metadata for `cz.doucko.content` (store spec content-authoring, FR-1 to FR-8, FR-10, FR-11,
 * FR-13, NFR-17): PDF sources, tasks with answer parts, hints, translations and images, the task list,
 * preview, answer tester and bulk publish, all under `/api/admin/`. Uploaded files go through
 * `FileStoragePort` to a directory outside every repository. Nothing is exposed to other modules yet;
 * missions (slice 6) and practice (slice 7) add a public API when they read content.
 */
@PackageInfo
@ApplicationModule(displayName = "Content authoring")
class ContentModule
