package cz.doucko.profile

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Package metadata for `cz.doucko.profile` (store spec child-profile, FR-43): the single child profile,
 * created on the first start from `DOUCKO_CHILD_DISPLAY_NAME`, and its practice settings behind
 * `/api/child/profile/settings`. The display name is private (NFR-3): it is stored, never logged.
 * Public API for other modules: [ChildProfile] (the display name for HQ and the translation setting, read by
 * practice).
 */
@PackageInfo
@ApplicationModule(displayName = "Child profile")
class ProfileModule
