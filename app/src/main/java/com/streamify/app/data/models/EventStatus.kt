package com.streamify.app.data.models

import com.google.gson.annotations.SerializedName

/**
 * Phase 2 \u00b7 Step 2.1 \u2014 Event lifecycle state.
 *
 *  DRAFT       \u2014 admin is editing; not yet visible in any list.
 *  SCHEDULED   \u2014 visible in the Home "Live Events" tab with a countdown.
 *  LIVE        \u2014 streams are playable; row shows animated LIVE badge.
 *  ENDED       \u2014 kept on the home as a "Recent" entry for ~24 h, then archived.
 */
enum class EventStatus {
    @SerializedName("DRAFT")     DRAFT,
    @SerializedName("SCHEDULED") SCHEDULED,
    @SerializedName("LIVE")      LIVE,
    @SerializedName("ENDED")     ENDED
}
