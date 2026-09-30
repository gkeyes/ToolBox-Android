'use strict';

const report = globalThis.runtimeMediaReport = {
  phase: 'loading', violations: [], fatal: null, trusted: false,
  videoTime: 0, audioTime: 0, videoFrames: 0,
};
const video = document.getElementById('video');
const audio = document.getElementById('audio');
const sessions = [];
video.muted = audio.muted = true;
video.loop = audio.loop = true;
document.addEventListener('securitypolicyviolation', event => report.violations.push(event.violatedDirective));
const fail = error => { report.fatal = String(error?.stack || error?.message || error); };

function metadata(player) {
  if (player.readyState >= 1) return Promise.resolve();
  return new Promise((resolve, reject) => {
    player.addEventListener('loadedmetadata', resolve, { once: true });
    player.addEventListener('error', () => reject(new Error('Media metadata error ' + player.error?.code)), { once: true });
  });
}

async function initialize() {
  await ToolBox.ready();
  const [videoSession, audioSession] = await Promise.all([
    ToolBox.network.openMedia({ url: 'https://cdn.example.test/video.mp4', kind: 'video' }),
    ToolBox.network.openMedia({ url: 'https://cdn.example.test/audio.mp3', kind: 'audio' }),
  ]);
  sessions.push(videoSession, audioSession);
  report.sessions = sessions;
  video.src = videoSession.url;
  audio.src = audioSession.url;
  await Promise.all([metadata(video), metadata(audio)]);
  report.videoDuration = video.duration;
  report.audioDuration = audio.duration;
  report.videoWidth = video.videoWidth;
  const partial = await fetch(videoSession.url, { headers: { Range: 'bytes=8-31' } });
  report.range = {
    status: partial.status,
    contentRange: partial.headers.get('content-range'),
    bytes: Array.from(new Uint8Array(await partial.arrayBuffer())),
  };
  report.phase = 'ready';
  document.querySelectorAll('button').forEach(button => button.disabled = false);
}

document.getElementById('play').addEventListener('click', event => {
  report.trusted = event.isTrusted;
  Promise.all([video.play(), audio.play()]).then(() => { report.phase = 'playing'; }).catch(fail);
});

document.getElementById('seek').addEventListener('click', () => {
  video.pause();
  audio.pause();
  const seek = (player, key) => new Promise(resolve => {
    const target = player.duration * 0.65;
    report[key + 'Target'] = target;
    player.addEventListener('seeked', () => {
      report[key + 'Time'] = player.currentTime;
      resolve();
    }, { once: true });
    player.currentTime = target;
  });
  Promise.all([seek(video, 'videoSeek'), seek(audio, 'audioSeek')])
    .then(() => { report.phase = 'seeked'; }).catch(fail);
});

document.getElementById('close').addEventListener('click', async () => {
  try {
    for (const player of [video, audio]) {
      player.pause();
      player.removeAttribute('src');
      player.load();
    }
    await Promise.all(sessions.map(session => ToolBox.network.closeMedia(session.sessionId)));
    report.closedStatus = await Promise.all(sessions.map(session => fetch(session.url).then(response => response.status)));
    report.phase = 'closed';
  } catch (error) { fail(error); }
});

document.getElementById('hold').addEventListener('click', async () => {
  try {
    const session = await ToolBox.network.openMedia({ url: 'https://cdn.example.test/holding.mp4', kind: 'video' });
    report.held = session;
    report.phase = 'holding';
    fetch(session.url, { headers: { Range: 'bytes=0-' } }).then(response => response.arrayBuffer())
      .then(() => fail(new Error('The retained body unexpectedly completed')))
      .catch(error => { report.holdError = String(error.message); });
  } catch (error) { fail(error); }
});

document.getElementById('close-held').addEventListener('click', async () => {
  try {
    await ToolBox.network.closeMedia(report.held.sessionId);
    report.phase = 'held-closed';
  } catch (error) { fail(error); }
});

setInterval(() => {
  report.videoTime = Math.max(report.videoTime, video.currentTime);
  report.audioTime = Math.max(report.audioTime, audio.currentTime);
  report.videoFrames = video.getVideoPlaybackQuality?.().totalVideoFrames || 0;
}, 20);
initialize().catch(fail);
