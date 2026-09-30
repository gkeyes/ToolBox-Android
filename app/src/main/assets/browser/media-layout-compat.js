(function repairCollapsedMediaLayout() {
    'use strict';

    const MIN_WIDTH = 120;
    const MAX_ZERO_HEIGHT = 2;
    const PLAYER_HINT = /(dplayer|player|video|media|plyr|jwplayer|vjs)/i;

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

    function intrinsicRatio(video) {
        const width = Number(video.videoWidth || 0);
        const height = Number(video.videoHeight || 0);
        if (width < MIN_WIDTH || height < 60) return 0;
        const ratio = width / height;
        return ratio >= 0.4 && ratio <= 4 ? ratio : 0;
    }

    function preferredContainer(video) {
        let cursor = video.parentElement;
        let fallback = collapsed(video) ? video : null;
        let playerRoot = null;
        for (let depth = 0; cursor && depth < 7; depth++, cursor = cursor.parentElement) {
            if (!collapsed(cursor)) {
                if (fallback) break;
                continue;
            }
            fallback = cursor;
            const id = String(cursor.id || '');
            const classes = cursor.classList ? [...cursor.classList] : [];
            const highConfidence =
                classes.includes('dplayer') ||
                classes.includes('plyr') ||
                classes.includes('video-js') ||
                cursor.localName === 'jwplayer' ||
                /^(?:player|video-player|media-player)(?:-|$)/i.test(id);
            if (highConfidence) playerRoot = cursor;
            else if (!playerRoot && PLAYER_HINT.test(id + ' ' + classes.join(' '))) playerRoot = cursor;
        }
        return playerRoot || fallback;
    }

    function repair(video) {
        if (!video || !video.isConnected || video.dataset.toolboxLayoutRepairDone === '1') return false;
        if (!collapsed(video) || video.readyState < 1 || !(video.currentSrc || video.src)) return false;

        const ratio = intrinsicRatio(video);
        if (!ratio) return false;

        const target = preferredContainer(video);
        if (!target) return false;
        const rect = target.getBoundingClientRect();
        const height = Math.round(rect.width / ratio);
        if (height < 80 || height > Math.max(window.innerHeight * 1.5, 1200)) return false;

        const targetStyle = getComputedStyle(target);
        target.dataset.toolboxMediaLayoutRepair = Math.round(video.videoWidth) + 'x' + Math.round(video.videoHeight);
        target.style.setProperty('min-height', height + 'px', 'important');
        if (Number.parseFloat(targetStyle.height || '0') <= MAX_ZERO_HEIGHT) {
            target.style.setProperty('height', height + 'px', 'important');
        }
        video.dataset.toolboxLayoutRepairDone = '1';
        return true;
    }

    let repaired = 0;
    for (const video of document.querySelectorAll('video')) {
        if (repair(video)) {
            repaired++;
            continue;
        }
        if (collapsed(video) && video.dataset.toolboxLayoutRepairListener !== '1') {
            video.dataset.toolboxLayoutRepairListener = '1';
            const once = () => {
                repair(video);
                video.removeEventListener('loadedmetadata', once);
                video.removeEventListener('canplay', once);
            };
            video.addEventListener('loadedmetadata', once, { once: true, passive: true });
            video.addEventListener('canplay', once, { once: true, passive: true });
        }
    }
    return repaired;
})
