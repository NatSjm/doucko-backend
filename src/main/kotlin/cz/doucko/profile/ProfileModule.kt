package cz.doucko.profile

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Package metadata for `cz.doucko.profile` (store spec child-profile, FR-43): the single child profile,
 * created on the first start from `DOUCKO_CHILD_DISPLAY_NAME`, and its practice settings behind
 * `/api/child/profile/settings`. The display name is private (NFR-3): it is stored, never logged.
 * Nothing is exposed to other modules yet; the HQ and practice slices add a public API when they read it.
 */
@PackageInfo
@ApplicationModule(displayName = "Child profile")
class ProfileModule
