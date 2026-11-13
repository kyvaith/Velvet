# Diagnostics and privacy

Velvet exports one text report containing the current run and up to four earlier runs. Events are appended as they occur, so one report can contain all errors from an attempted viewing session instead of only the last error.

Use the diagnostic export in the app's settings after reproducing a problem. Include the app version, device model, Android version, and a short description of the visible behavior when reporting an issue. There is no need to provide your password, cookies, or account tokens.

## Reading a report

Timestamps use UTC. `elapsedMs` uses a monotonic clock. An `operationId` correlates the beginning and result of an operation; repeated exception entries at several layers can describe the same underlying failure.

The stages distinguish account activation, native manifest retrieval, license acquisition, DRM key loading, player readiness, the first rendered frame, and actual playing state. HTTP 200 alone does not establish successful playback: the encrypted response can contain an application error.

Player loading summaries aggregate completed, failed, and canceled loads. A temporary `BUFFERING` state followed by `READY` is not itself a failed request. Interpret it alongside load errors, player exceptions, and subsequent playing state.

## Export policy

`DiagnosticPrivacy` accepts explicitly listed numeric fields, flags, and known categories. The exporter excludes account credentials and raw protocol contents.

| Included | Excluded |
| --- | --- |
| App/device versions, event times, counts, durations, and stages | Passwords, cookie values, NFToken, and owner/profile tokens |
| Known numeric or allowlisted service codes | Raw server messages, response bodies, arbitrary text codes, and URLs |
| Cookie flags and matching-category metadata | Cookie header values and account identifiers |
| Known DRM levels and structural challenge metadata | ESNs, DRM payload contents, private device credentials, and content keys |
| Manifest/track counts and playback state | Profile IDs, title IDs, search phrases, and stream addresses |
| Exception classes and bounded stack information | Exception messages that may contain account or transport data |

The report still describes your device and activity timing. Review an export before making it public. Raw user reports are not included in the source repository; verification documentation uses aggregate facts.

## Service-error parsing

The parser recognizes numeric MSL/Blade Runner codes and a closed list of native NQ codes, including account, stream-quota, PIN, availability, and verification errors. Unknown strings are omitted; presence and type metadata can still show that an unrecognized field existed.

Only known error containers are traversed: root/result errors, `innerErrors`, and `deviceCommand`. Device commands are classified for diagnostics, not executed. Parsing is bounded by depth, node count, and error count. A truncated traversal does not authorize automatic retry. An unrecognized code is reported as unrecognized, not as proof that Netflix supplied no code.

## Reported successful run

The November 13, 2026 report from version 0.1.11 contains **281 INFO, 0 WARN, and 0 ERROR** entries, with no suppressed events or errors recorded. Two attempts reached native manifest selection, a successful license response, Widevine key loading, first-frame rendering, `READY`, and `isPlaying=true`. Both closed by user action with successful local DRM cleanup.

One short buffering interval recovered to `READY`; no associated failure was recorded. The report does not establish its cause. These findings do not justify a speculative playback fix.

