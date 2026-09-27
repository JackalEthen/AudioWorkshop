# Personal Audio Tool

This context describes a personal Android tool that resolves shared music links or imports local audio, edits songs non-destructively, and exports a self-contained MP3 package.

## Language

**Share Input**:
Raw share text or a URL supplied by the user as the starting point of a resolution.
_Avoid_: Song link, download link

**Resolution**:
The process of interpreting one Share Input into a SourceTrack and its available media and lyrics.
_Avoid_: Parsing a song, downloading

**SourceTrack**:
An immutable audio source created by a successful Resolution or by a local import. It is never modified by editing or export.
_Avoid_: Song, music file, current song

**Track Metadata**:
Descriptive information belonging to a SourceTrack, including title, artist, album, artwork, duration, and format facts.
_Avoid_: Song information, tags

**Lyrics Track**:
The timed lyric content associated with a SourceTrack, including line and word timing when available.
_Avoid_: Lyrics text, subtitle

**Download Task**:
A resumable attempt to acquire the audio resource of a SourceTrack, with its own progress and lifecycle.
_Avoid_: Download history, download record

**Parse Record**:
The retained outcome of a Resolution, including success or failure and the recovered SourceTrack summary.
_Avoid_: Resolution history, search history

**Edit Project**:
A non-destructive set of changes applied to one SourceTrack. The SourceTrack remains unchanged.
_Avoid_: Edited song, output song

**Edit Segment**:
One continuous source-time interval retained or removed by an Edit Project.
_Avoid_: Clip, fragment

**Keep Selection**:
An edit interpretation that retains selected intervals and joins them in source order.
_Avoid_: Keep mode

**Remove Selection**:
An edit interpretation that removes selected intervals and joins the remaining intervals in source order.
_Avoid_: Delete mode

**Edit Operation**:
A typed change such as trimming, splitting, joining, fading, gain adjustment, or lyric timing correction.
_Avoid_: Effect, filter

**Export Package**:
An immutable MP3 produced from a SourceTrack and an Edit Project, containing the audio, Track Metadata, artwork, and Lyrics Track.
_Avoid_: Download, rendered file

**History Entry**:
A user-visible summary of a completed or attempted Parse Record, Download Task, edit operation, or Export Package.
_Avoid_: Log, activity

## Invariants

- A SourceTrack is never overwritten.
- Every Edit Project references exactly one SourceTrack.
- Every Export Package is derived from one SourceTrack and one Edit Project.
- Preview and export use the same timing transformation rules.
- Lyrics timing follows the same retained and removed intervals as audio.
- A Download Task and its file are separate: deleting the record does not necessarily delete the file.
