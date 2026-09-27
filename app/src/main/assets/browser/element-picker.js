/* Original ToolBox picker. No extension code or privileged page-to-native bridge. */
(function installToolBoxFilter(key, selectors, expectedHost) {
    'use strict';
    if (window.top !== window || !/^https?:$/.test(location.protocol) || location.hostname.toLowerCase().replace(/\.$/, '') !== expectedHost) return false;
    if (window[key]) { window[key].apply(selectors); return true; }
    const root = document.documentElement;
    if (!root) return false;
    const permanent = document.createElement('style');
    const temporary = document.createElement('style');
    root.append(permanent, temporary);
    let shield = null, outline = null, target = null, chain = [], depth = 0;
    let draft = '', matches = 0, warning = '', preview = false, active = false, frame = 0, down = null;
    const own = node => node === permanent || node === temporary || node === shield || node === outline;
    const allowed = selector => typeof selector === 'string' && selector.length > 0 && selector.length <= 1024 && !/[{};@\x00\r\n]/.test(selector) && !selector.includes('/*') && !selector.includes('*/');
    function inspect(selector) {
        if (!allowed(selector)) return { valid: false, count: 0, message: '请输入有效的 CSS 选择器，不支持样式或脚本。' };
        try {
            const nodes = [...document.querySelectorAll(selector)].filter(node => !own(node));
            if (nodes.some(node => node === root || node === document.body)) return { valid: false, count: nodes.length, message: '不能隐藏整个页面，请缩小范围。' };
            if (nodes.length > 200) return { valid: false, count: nodes.length, message: '匹配超过 200 处，请缩小范围。' };
            return { valid: true, count: nodes.length, message: nodes.length ? '' : '当前页面没有匹配，保存后将在匹配的页面生效。' };
        } catch (_) { return { valid: false, count: 0, message: '选择器语法无效。' }; }
    }
    function apply(list) {
        permanent.textContent = list.filter(selector => inspect(selector).valid).map(selector => `:is(${selector}):not(html):not(body){display:none!important;}`).join('\n');
        if (!permanent.isConnected) root.append(permanent);
    }
    const stable = token => token.length < 70 && !/\d{4,}|[a-f\d]{10,}|^(css|jsx|sc)-/i.test(token);
    function fragment(node) {
        if (node.id && stable(node.id)) return '#' + CSS.escape(node.id);
        const classes = [...node.classList].filter(stable).slice(0, 2);
        return node.localName + classes.map(name => '.' + CSS.escape(name)).join('');
    }
    function candidate(node) {
        const parts = [fragment(node)];
        let cursor = node, positional = false;
        for (let level = 0; level < 5; level++) {
            const result = inspect(parts.join(' > '));
            if (result.valid && result.count === 1) return { selector: parts.join(' > '), positional };
            const parent = cursor.parentElement;
            if (!parent) break;
            const siblings = [...parent.children].filter(el => el.localName === cursor.localName);
            if (siblings.length > 1) {
                parts[0] = fragment(cursor) + ':nth-of-type(' + (siblings.indexOf(cursor) + 1) + ')';
                positional = true;
                const narrowed = inspect(parts.join(' > '));
                if (narrowed.valid && narrowed.count === 1) break;
            }
            if (parent === document.body || parent === root) break;
            cursor = parent;
            parts.unshift(fragment(parent));
        }
        return { selector: parts.join(' > '), positional };
    }

    function paint() {
        frame = 0;
        if (!outline) return;
        if (!target || !target.isConnected || preview) { outline.style.display = 'none'; return; }
        const rect = target.getBoundingClientRect();
        Object.assign(outline.style, { display: 'block', left: rect.left + 'px', top: rect.top + 'px', width: rect.width + 'px', height: rect.height + 'px' });
    }
    function schedulePaint() { if (!frame) frame = requestAnimationFrame(paint); }
    function pick(node) {
        if (!node || own(node) || node === root || node === document.body) return;
        target = node;
        const result = candidate(node);
        draft = result.selector;
        const check = inspect(draft);
        matches = check.count;
        warning = !check.valid ? check.message : result.positional ? '此规则依赖页面位置，网站改版后可能需要重选。' : '';
        temporary.textContent = '';
        preview = false;
        paint();
    }
    function hit(x, y) {
        shield.style.pointerEvents = 'none';
        const node = document.elementFromPoint(x, y);
        shield.style.pointerEvents = 'auto';
        chain = [];
        for (let cursor = node; cursor && cursor !== root && cursor !== document.body && !own(cursor); cursor = cursor.parentElement) chain.push(cursor);
        depth = 0;
        pick(chain[0]);
    }
    function cancelClick(event) { event.preventDefault(); event.stopImmediatePropagation(); }
    function start() {
        stop(); active = true;
        shield = document.createElement('div'); outline = document.createElement('div');
        shield.setAttribute('aria-label', '选择要屏蔽的网页区域');
        shield.style.cssText = 'position:fixed!important;inset:0!important;z-index:2147483647!important;background:transparent!important;touch-action:pan-y pinch-zoom!important;';
        outline.style.cssText = 'display:none;position:fixed!important;pointer-events:none!important;z-index:2147483646!important;box-sizing:border-box!important;border:2px solid #1677ff!important;background:rgba(22,119,255,.16)!important;border-radius:4px!important;';
        shield.addEventListener('pointerdown', event => { down = { x: event.clientX, y: event.clientY }; event.stopImmediatePropagation(); });
        shield.addEventListener('pointerup', event => {
            event.stopImmediatePropagation();
            if (down && Math.hypot(event.clientX - down.x, event.clientY - down.y) < 12) hit(event.clientX, event.clientY);
            down = null;
        });
        shield.addEventListener('pointercancel', () => { down = null; });
        shield.addEventListener('click', cancelClick, true);
        shield.addEventListener('contextmenu', cancelClick, true);
        root.append(outline, shield);
        window.addEventListener('scroll', schedulePaint, true);
        window.addEventListener('resize', schedulePaint);
        window.visualViewport?.addEventListener('resize', schedulePaint);
        window.visualViewport?.addEventListener('scroll', schedulePaint);
        return state();
    }
    function stop() {
        active = false; preview = false; target = null; draft = ''; matches = 0; warning = ''; chain = []; depth = 0;
        temporary.textContent = ''; shield?.remove(); outline?.remove(); shield = outline = null;
        window.removeEventListener('scroll', schedulePaint, true); window.removeEventListener('resize', schedulePaint);
        window.visualViewport?.removeEventListener('resize', schedulePaint); window.visualViewport?.removeEventListener('scroll', schedulePaint);
        if (frame) cancelAnimationFrame(frame); frame = 0;
    }
    function state() {
        if (target && !target.isConnected) { temporary.textContent = ''; target = null; draft = ''; matches = 0; preview = false; warning = '页面内容已变化，请重新选择。'; paint(); }
        return { active, selector: draft, count: matches, warning, preview, canShrink: depth > 0, canExpand: depth + 1 < chain.length };
    }
    function adjust(delta) {
        if (!active || !chain.length) return state();
        depth = Math.max(0, Math.min(chain.length - 1, depth + delta)); pick(chain[depth]); return state();
    }
    function edit(selector) {
        temporary.textContent = ''; preview = false;
        draft = selector; const check = inspect(selector); matches = check.count; warning = check.message; paint(); return state();
    }
    function showPreview() {
        const check = inspect(draft);
        if (!check.valid || !check.count) { warning = check.message || '请先选择要隐藏的区域。'; return state(); }
        preview = !preview; temporary.textContent = preview ? `:is(${draft}):not(html):not(body){display:none!important;}` : ''; paint(); return state();
    }
    window[key] = { apply, start, stop, state, adjust, edit, inspect, preview: showPreview };
    apply(selectors);
    return true;
})
