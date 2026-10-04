package cz.doucko.admin

import org.springframework.modulith.ApplicationModule
import org.springframework.modulith.PackageInfo

/**
 * Package metadata for `cz.doucko.admin` (store spec admin-access): the single admin's password,
 * session and CSRF protection for `/api/admin/` (NFR-1, NFR-15, NFR-16). Later admin slices only
 * add handlers behind this chain; they never call into this module.
 */
@PackageInfo
@ApplicationModule(displayName = "Admin access")
class AdminModule
