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
  kind: "VIDEO" | "PLAYLIST" | "CHANNEL" | "DOWNLOAD";
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
  currentVideoId: string | null;
  fileName: string | null;
  options: {
    videoFormatId: string | null;
    audioFormatId: string | null;
    subtitles: boolean;
    sponsorBlock: boolean;
  };
};

export type QueueSnapshot = { jobs: QueueJob[]; beforeYou: number };

export type UserSettings = { autoplay: boolean; hideTechnicalDetails: boolean };
