'use strict';

const report = globalThis.runtimeMediaReport = {
  phase: 'loading', violations: [], fatal: null, trusted: false,
  videoTime: 0, audioTime: 0, videoFrames: 0, events: [], stages: [], media: {},
};
const started = performance.now();
const video = document.getElementById('video');
const audio = document.getElementById('audio');
const sessions = [];
video.muted = audio.muted = true;
video.loop = audio.loop = true;

function mediaState(player) {
  return {
    readyState: player.readyState, networkState: player.networkState,
    currentTime: player.currentTime, duration: player.duration,
    currentSrc: player.currentSrc, paused: player.paused, ended: player.ended,
    error: player.error && { code: player.error.code, message: player.error.message },
  };
}

function snapshot() {
  report.media = { video: mediaState(video), audio: mediaState(audio) };
}

function stage(phase) {
  report.phase = phase;
  snapshot();
  report.stages.push({ phase, elapsed: Math.round(performance.now() - started) });
  if (report.stages.length > 32) report.stages.shift();
  console.info('RUNTIME_MEDIA_STAGE ' + JSON.stringify({ phase, media: report.media }));
}

for (const player of [video, audio]) {
  for (const type of ['loadstart', 'loadedmetadata', 'loadeddata', 'canplay', 'playing', 'waiting', 'suspend', 'stalled', 'error', 'abort', 'emptied', 'seeking', 'seeked']) {
    player.addEventListener(type, () => {
      snapshot();
      report.events.push({ player: player.id, type, elapsed: Math.round(performance.now() - started) });
      if (report.events.length > 64) report.events.shift();
    });
  }
}
document.addEventListener('securitypolicyviolation', event => report.violations.push(event.violatedDirective));
const fail = error => {
  report.fatal = String(error?.stack || error?.message || error);
  snapshot();
  console.error('RUNTIME_MEDIA_FATAL ' + report.fatal);
};
globalThis.addEventListener('error', event => fail(event.error || event.message));
globalThis.addEventListener('unhandledrejection', event => fail(event.reason));

function metadata(player) {
  if (player.readyState >= 1) return Promise.resolve();
  return new Promise((resolve, reject) => {
    player.addEventListener('loadedmetadata', resolve, { once: true });
    player.addEventListener('error', () => reject(new Error('Media metadata error ' + player.error?.code)), { once: true });
  });
}

async function initialize() {
  stage('sdk-ready-pending');
  await ToolBox.ready();
  stage('sdk-ready');
  stage('sessions-open-pending');
  const [videoSession, audioSession] = await Promise.all([
    ToolBox.network.openMedia({ url: 'https://cdn.example.test/video.mp4', kind: 'video' }).then(session => {
      report.videoSessionOpened = true;
      return session;
    }),
    ToolBox.network.openMedia({ url: 'https://cdn.example.test/audio.mp3', kind: 'audio' }).then(session => {
      report.audioSessionOpened = true;
      return session;
    }),
  ]);
  sessions.push(videoSession, audioSession);
  report.sessions = sessions;
  stage('sessions-open');
  video.src = videoSession.url;
  audio.src = audioSession.url;
  stage('metadata-pending');
  await Promise.all([metadata(video), metadata(audio)]);
  report.videoDuration = video.duration;
  report.audioDuration = audio.duration;
  report.videoWidth = video.videoWidth;
  stage('metadata-loaded');
  stage('range-probe-pending');
  const partial = await fetch(videoSession.url, { headers: { Range: 'bytes=8-31' } });
  report.rangeStatus = partial.status;
  stage('range-body-pending');
  report.range = {
    status: partial.status,
    contentRange: partial.headers.get('content-range'),
    bytes: Array.from(new Uint8Array(await partial.arrayBuffer())),
  };
  const totalLength = Number(report.range.contentRange?.split('/')[1]);
  if (!Number.isSafeInteger(totalLength) || totalLength <= 32) {
    throw new Error('Invalid range probe response ' + JSON.stringify(report.range));
  }
  const probe = async (range, method = 'GET') => {
    const response = await fetch(videoSession.url, { method, headers: { Range: range } });
    return {
      status: response.status,
      contentRange: response.headers.get('content-range'),
      contentLength: response.headers.get('content-length'),
      bodyLength: (await response.arrayBuffer()).byteLength,
    };
  };
  stage('range-head-pending');
  report.headRange = await probe('bytes=8-31', 'HEAD');
  stage('range-suffix-pending');
  report.suffixRange = await probe('bytes=-32');
  stage('range-unsatisfiable-pending');
  report.unsatisfiableRange = await probe('bytes=' + totalLength + '-');
  stage('ready');
  document.querySelectorAll('button').forEach(button => button.disabled = false);
}

document.getElementById('play').addEventListener('click', event => {
  report.trusted = event.isTrusted;
  stage('play-pending');
  Promise.all([video.play(), audio.play()]).then(() => { stage('playing'); }).catch(fail);
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
    .then(() => { stage('seeked'); }).catch(fail);
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
    stage('closed');
  } catch (error) { fail(error); }
});

document.getElementById('hold').addEventListener('click', async () => {
  try {
    const session = await ToolBox.network.openMedia({ url: 'https://cdn.example.test/holding.mp4', kind: 'video' });
    report.held = session;
    stage('holding');
    fetch(session.url, { headers: { Range: 'bytes=0-' } }).then(response => response.arrayBuffer())
      .then(() => fail(new Error('The retained body unexpectedly completed')))
      .catch(error => { report.holdError = String(error.message); });
  } catch (error) { fail(error); }
});

document.getElementById('close-held').addEventListener('click', async () => {
  try {
    await ToolBox.network.closeMedia(report.held.sessionId);
    stage('held-closed');
  } catch (error) { fail(error); }
});

setInterval(() => {
  report.videoTime = Math.max(report.videoTime, video.currentTime);
  report.audioTime = Math.max(report.audioTime, audio.currentTime);
  report.videoFrames = video.getVideoPlaybackQuality?.().totalVideoFrames || 0;
  snapshot();
}, 20);
initialize().catch(fail);
