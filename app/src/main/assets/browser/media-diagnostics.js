(function collectToolBoxMediaDiagnostics() {
    'use strict';

    const lines = [];
    const add = (label, value) => lines.push(label + ': ' + String(value));
    const clean = value => String(value == null ? '' : value).replace(/[\r\n\t]+/g, ' ').trim().slice(0, 220);
    const safeUrl = value => {
        try {
            const url = new URL(value, location.href);
            if (!/^https?:$/.test(url.protocol)) return clean(url.protocol);
            return (url.origin + url.pathname).slice(0, 240);
        } catch (_) {
            return clean(value).split('?')[0].split('#')[0];
        }
    };
    const rect = node => {
        const box = node.getBoundingClientRect();
        return Math.round(box.width) + 'x' + Math.round(box.height);
    };
    const styleState = node => {
        const style = getComputedStyle(node);
        return 'display=' + style.display +
            ', visibility=' + style.visibility +
            ', opacity=' + style.opacity +
            ', rect=' + rect(node) +
            ', hidden=' + Boolean(node.hidden);
    };
    const describe = node => {
        const id = node.id ? '#' + clean(node.id) : '';
        const classes = node.classList && node.classList.length
            ? '.' + [...node.classList].slice(0, 4).map(clean).join('.')
            : '';
        return node.localName + id + classes;
    };
    const encode = text => {
        if (typeof TextEncoder === 'function') {
            const bytes = new TextEncoder().encode(text);
            let binary = '';
            const chunk = 0x4000;
            for (let index = 0; index < bytes.length; index += chunk) {
                binary += String.fromCharCode(...bytes.subarray(index, index + chunk));
            }
            return btoa(binary);
        }
        return btoa(unescape(encodeURIComponent(text)));
    };

    add('Page', safeUrl(location.href));
    add('readyState', document.readyState);
    add('visibility', document.visibilityState);
    add('User-Agent', navigator.userAgent);
    if (navigator.userAgentData) {
        add('UA-CH', 'mobile=' + navigator.userAgentData.mobile + ', platform=' + navigator.userAgentData.platform);
    }

    const probe = document.createElement('video');
    const mediaSourceAvailable = typeof MediaSource === 'function';
    add('DPlayer global', typeof window.DPlayer);
    add('Hls global', typeof window.Hls);
    add('Hls.isSupported', typeof window.Hls?.isSupported === 'function' ? window.Hls.isSupported() : 'n/a');
    add('MediaSource', mediaSourceAvailable);
    add(
        'MSE H264/AAC',
        mediaSourceAvailable && typeof MediaSource.isTypeSupported === 'function'
            ? MediaSource.isTypeSupported('video/mp4; codecs="avc1.42E01E, mp4a.40.2"')
            : 'n/a',
    );
    add('native HLS', probe.canPlayType('application/vnd.apple.mpegurl') || 'no');
    add('MP4 H264', probe.canPlayType('video/mp4; codecs="avc1.42E01E, mp4a.40.2"') || 'no');

    const videos = [...document.querySelectorAll('video')];
    const sources = [...document.querySelectorAll('source')];
    const frames = [...document.querySelectorAll('iframe')];
    const dplayers = [...document.querySelectorAll('.dplayer,[class*="dplayer"],[id*="dplayer"]')];
    const playerLike = [...document.querySelectorAll(
        '.dplayer,[class*="player"],[id*="player"],[class*="video"],[id*="video"],video,iframe'
    )];

    add('video count', videos.length);
    add('source count', sources.length);
    add('iframe count', frames.length);
    add('DPlayer DOM count', dplayers.length);
    add('player-like count', playerLike.length);
    add('body text has DPlayer marker', /DPlayer\s+v?\d/i.test(document.body?.innerText || ''));

    videos.slice(0, 8).forEach((video, index) => {
        const error = video.error ? ('code=' + video.error.code + ' ' + clean(video.error.message || '')) : 'none';
        const style = getComputedStyle(video);
        lines.push(
            'video[' + index + '] ' + describe(video) + ' · ' + styleState(video) +
            ' · intrinsic=' + video.videoWidth + 'x' + video.videoHeight +
            ' · attr=' + clean(video.getAttribute('width') || '') + 'x' + clean(video.getAttribute('height') || '') +
            ' · aspect=' + clean(style.aspectRatio) +
            ' · repair=' + clean(video.dataset.toolboxLayoutRepairRatio || 'none') +
            ' · ready=' + video.readyState + ' network=' + video.networkState +
            ' paused=' + video.paused + ' error=' + error +
            ' · src=' + safeUrl(video.currentSrc || video.src || ''),
        );
    });

    sources.slice(0, 10).forEach((source, index) => {
        lines.push('source[' + index + '] type=' + clean(source.type) + ' · ' + safeUrl(source.src || ''));
    });

    frames.slice(0, 8).forEach((frame, index) => {
        lines.push('iframe[' + index + '] ' + styleState(frame) + ' · ' + safeUrl(frame.src || ''));
    });

    const seen = new Set();
    playerLike.slice(0, 24).forEach(node => {
        const key = describe(node);
        if (seen.has(key)) return;
        seen.add(key);
        const style = getComputedStyle(node);
        lines.push(
            'container ' + key + ' · ' + styleState(node) +
            ' · aspect=' + clean(style.aspectRatio) +
            ' · repair=' + clean(node.dataset.toolboxMediaLayoutRepair || 'none') +
            ' · childVideos=' + node.querySelectorAll('video').length,
        );
    });

    const playerScripts = [...document.scripts]
        .map(script => script.src)
        .filter(src => src && /(dplayer|hls(?:\.min)?\.js|player|video)/i.test(src))
        .slice(0, 20);
    if (playerScripts.length) {
        lines.push('player scripts:');
        playerScripts.forEach(src => lines.push('  - ' + safeUrl(src)));
    }

    if (performance?.getEntriesByType) {
        const resources = performance.getEntriesByType('resource')
            .filter(entry => {
                const name = String(entry.name || '');
                return /\.(m3u8|m3u|mp4|m4s|ts|webm|mpd)(?:$|[?#])/i.test(name) ||
                    /(dplayer|hls(?:\.min)?\.js|player)/i.test(name);
            })
            .slice(-24);
        if (resources.length) {
            lines.push('media performance resources:');
            resources.forEach(entry => {
                lines.push(
                    '  - ' + clean(entry.initiatorType) +
                    ' ' + safeUrl(entry.name) +
                    ' · duration=' + Math.round(entry.duration) + 'ms' +
                    ' transfer=' + (entry.transferSize || 0),
                );
            });
        }
    }

    return encode(lines.join('\n'));
})
