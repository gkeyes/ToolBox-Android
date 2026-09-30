(function repairCollapsedMediaLayout() {
    'use strict';

    const MIN_WIDTH = 120;
    const MAX_ZERO_HEIGHT = 2;
    const FALLBACK_RATIO = 16 / 9;
    const PLAYER_HINT = /(dplayer|player|video|media|plyr|jwplayer|vjs)/i;
    const DIRECT_MEDIA_HINT = /\.(?:m3u8|m3u|mp4|webm|mpd|mov)(?:$|[?#])/i;

    function visible(node) {
        const style = getComputedStyle(node);
        return style.display !== 'none' &&
            style.visibility !== 'hidden' &&
            Number.parseFloat(style.opacity || '1') > 0;
    }

    function collapsed(node) {
        const rect = node.getBoundingClientRect();
        return visible(node) && rect.width >= MIN_WIDTH && rect.height <= MAX_ZERO_HEIGHT;
    }

    function validRatio(width, height) {
        width = Number(width || 0);
        height = Number(height || 0);
        if (width < 1 || height < 1) return 0;
        const ratio = width / height;
        return ratio >= 0.4 && ratio <= 4 ? ratio : 0;
    }

    function parseAspectRatio(value) {
        value = String(value || '').trim();
        if (!value || value === 'auto') return 0;
        const match = value.match(/^([0-9.]+)\s*\/\s*([0-9.]+)$/);
        if (match) return validRatio(Number(match[1]), Number(match[2]));
        const numeric = Number(value);
        return Number.isFinite(numeric) && numeric >= 0.4 && numeric <= 4 ? numeric : 0;
    }

    function ratioFromVideo(video) {
        return validRatio(video.videoWidth, video.videoHeight) ||
            validRatio(video.getAttribute('width'), video.getAttribute('height')) ||
            parseAspectRatio(getComputedStyle(video).aspectRatio);
    }

    function mediaSource(video) {
        return String(
            video.currentSrc ||
            video.src ||
            video.querySelector('source[src]')?.src ||
            ''
        ).trim();
    }

    function preferredContainer(video) {
        let cursor = video.parentElement;
        let fallback = collapsed(video) ? video : null;
        let hinted = null;
        let highConfidence = null;
        const path = [video];

        for (let depth = 0; cursor && depth < 8; depth++, cursor = cursor.parentElement) {
            if (!collapsed(cursor)) {
                if (fallback) break;
                continue;
            }

            path.push(cursor);
            fallback = cursor;
            const id = String(cursor.id || '');
            const classes = cursor.classList ? [...cursor.classList] : [];
            const hintText = id + ' ' + classes.join(' ');
            const isHighConfidence =
                classes.includes('dplayer') ||
                classes.includes('plyr') ||
                classes.includes('video-js') ||
                cursor.localName === 'jwplayer' ||
                /^(?:player|video-player|media-player)(?:-|$)/i.test(id);

            if (isHighConfidence) highConfidence = cursor;
            else if (!hinted && PLAYER_HINT.test(hintText)) hinted = cursor;
        }

        const node = highConfidence || hinted || fallback;
        return {
            node,
            highConfidence: Boolean(highConfidence),
            path: node ? path.slice(0, path.indexOf(node) + 1) : [],
        };
    }

    function ratioFor(video, target) {
        const direct = ratioFromVideo(video);
        if (direct) return { ratio: direct, source: 'intrinsic' };

        const targetRatio = parseAspectRatio(getComputedStyle(target).aspectRatio);
        if (targetRatio) return { ratio: targetRatio, source: 'css' };

        const parentRatio = video.parentElement
            ? parseAspectRatio(getComputedStyle(video.parentElement).aspectRatio)
            : 0;
        if (parentRatio) return { ratio: parentRatio, source: 'parent-css' };

        return { ratio: 0, source: '' };
    }

    function canUseFallback(video, source, highConfidence) {
        if (!highConfidence || !source) return false;
        if (video.readyState >= 2) return true;
        if (source.startsWith('blob:')) return true;
        if (/^https?:/i.test(source) && DIRECT_MEDIA_HINT.test(source)) return true;
        return false;
    }

    function repair(video) {
        if (!video || !video.isConnected || video.dataset.toolboxLayoutRepairDone === '1') return false;
        if (!collapsed(video)) return false;

        const source = mediaSource(video);
        if (!source) return false;

        const container = preferredContainer(video);
        const target = container.node;
        if (!target || !collapsed(target)) return false;

        let { ratio, source: ratioSource } = ratioFor(video, target);
        if (!ratio && canUseFallback(video, source, container.highConfidence)) {
            ratio = FALLBACK_RATIO;
            ratioSource = 'fallback-16:9';
        }
        if (!ratio) return false;

        const rect = target.getBoundingClientRect();
        const expectedHeight = Math.round(rect.width / ratio);
        if (expectedHeight < 80 || expectedHeight > Math.max(window.innerHeight * 1.5, 1200)) return false;

        target.dataset.toolboxMediaLayoutRepair = ratioSource + ':' + ratio.toFixed(4);
        target.style.setProperty('aspect-ratio', String(ratio), 'important');
        target.style.setProperty('height', 'auto', 'important');
        target.style.setProperty('min-height', '0', 'important');

        for (const node of container.path) {
            if (node === target) continue;
            if (!visible(node)) continue;
            const box = node.getBoundingClientRect();
            if (box.width < MIN_WIDTH || box.height > MAX_ZERO_HEIGHT) continue;
            node.style.setProperty('height', '100%', 'important');
            node.style.setProperty('min-height', '100%', 'important');
        }

        video.dataset.toolboxLayoutRepairDone = '1';
        video.dataset.toolboxLayoutRepairRatio = ratioSource;
        return target.getBoundingClientRect().height > MAX_ZERO_HEIGHT;
    }

    let repaired = 0;
    for (const video of document.querySelectorAll('video')) {
        if (repair(video)) {
            repaired++;
            continue;
        }

        if (collapsed(video) && video.dataset.toolboxLayoutRepairListener !== '1') {
            video.dataset.toolboxLayoutRepairListener = '1';
            const retry = () => repair(video);
            video.addEventListener('loadedmetadata', retry, { once: true, passive: true });
            video.addEventListener('canplay', retry, { once: true, passive: true });
            video.addEventListener('loadeddata', retry, { once: true, passive: true });
        }
    }

    return repaired;
})
