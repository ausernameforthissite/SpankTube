export type ApiFetch = (path: string, init?: RequestInit) => Promise<Response>;

export type VideoFormat = {
  formatId: string;
  mimeType: string | null;
  codecs: string | null;
  width: number | null;
  height: number | null;
  fps: number | null;
  bitrate: number | null;
  hasVideo: boolean;
  hasAudio: boolean;
  segmentUrl: string;
};

export type QueueJob = {
  id: string;
  kind: "VIDEO" | "PLAYLIST" | "CHANNEL" | "DOWNLOAD" | "REFRESH";
  targetId: string;
  ownerUsername: string;
  ownerRole: string;
  priority: number;
  status: string;
  totalCount: number;
  completedCount: number;
  failedCount: number;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  nextAttemptAt: string | null;
  error: string | null;
  purpose: "ARCHIVE" | "DOWNLOAD";
  stage: string;
  bytesDownloaded: number;
  totalBytes: number;
  speedBps: number;
  downloadUrl: string | null;
  title: string;
  width: number | null;
  height: number | null;
  videoCodec: string | null;
  audioCodec: string | null;
  audioBitrate: number | null;
  currentVideoId: string | null;
  fileName: string | null;
  updatedAt: string;
  options: {
    videoFormatId: string | null;
    audioFormatId: string | null;
    subtitles: boolean;
    sponsorBlock: boolean;
  };
};

export type QueueSnapshot = { jobs: QueueJob[]; beforeYou: number };

export type WatchProgress = {
  videoId: string;
  positionSeconds: number;
  watchPercent: number;
  lastAccessedAt: string;
};

export type WatchProgressMap = Record<string, WatchProgress>;

export type UserSettings = { autoplay: boolean; hideTechnicalDetails: boolean; preferBestQuality: boolean };

export type ArchiveVersion = {
  versionId: string;
  videoId: string;
  title: string;
  description: string | null;
  publishedAt: string | null;
  durationSeconds: number | null;
  sizeBytes: number;
  width: number | null;
  height: number | null;
  videoCodec: string | null;
  audioCodec: string | null;
  audioBitrate: number | null;
  channelId: string;
  channelName: string;
  channelUrl: string;
  channelAvatarUrl: string;
  downloadUrl: string;
  filename: string;
  current: boolean;
};

export type ArchiveAvailability = {
  archived: boolean;
  downloadUrl: string | null;
  filename: string | null;
  version: ArchiveVersion | null;
};
