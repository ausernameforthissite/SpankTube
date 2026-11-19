import shaka from "shaka-player/dist/shaka-player.compiled.js";

export class SubtitleDisplayer extends shaka.text.UITextDisplayer {
  append(cues: shaka.text.Cue[]) {
    for (const cue of cues) {
      if (/<\/?[a-z][^>]*>/i.test(cue.payload)) shaka.text.Cue.parseCuePayload(cue);
    }
    super.append(cues);
  }
}
