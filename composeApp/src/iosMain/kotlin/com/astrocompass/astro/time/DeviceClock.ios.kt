package com.astrocompass.astro.time

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

actual fun currentEpochMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()
