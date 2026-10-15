/* Local calendar: stores ISO calendar dates; never calls a platform date picker. */
'use strict';
window.ZZCalendar = (() => {
  const MIN_YEAR = 1900, MAX_YEAR = 2199;
  const key = d => `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`;
  const today = () => key(new Date());
  const parse = value => {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(value || '')) return null;
    const [y,m,d] = value.split('-').map(Number), result = new Date(y,m-1,d,12);
    return key(result) === value ? result : null;
  };
  const display = value => value ? value.replace(/^(\d+)-(\d+)-(\d+)$/,(_,y,m,d)=>`${y}年${Number(m)}月${Number(d)}日`) : '未设置';
  const root = document.createElement('dialog');
  root.id = 'calendar'; root.className = 'date-dialog'; root.setAttribute('aria-labelledby','calendar-title');
  root.innerHTML = `<div class="calendar-shell"><div class="dialog-head"><h2 id="calendar-title">选择日期</h2><button type="button" class="icon-button" data-calendar="cancel" aria-label="关闭日期选择">×</button></div><div class="calendar-body"><div id="calendar-content"></div></div><div class="calendar-footer"><span id="calendar-selection" aria-live="polite"></span><div class="calendar-footer-actions"><button type="button" class="button outline" data-calendar="cancel">取消</button><button type="button" class="button primary" data-calendar="confirm">确定</button></div></div></div>`;
  document.body.append(root);
  let state = null;
  function render(focusDate) {
    if (!state) return;
    const {year,month,view} = state;
    const header = `<div class="calendar-heading"><button type="button" class="calendar-arrow" data-calendar="prev" aria-label="上一${view==='days'?'个月':view==='years'?'组年份':'年'}">‹</button><div><button type="button" data-calendar="years" class="calendar-period" aria-label="选择年份">${year}年 ▾</button><button type="button" data-calendar="months" class="calendar-period" aria-label="选择月份">${month+1}月 ▾</button></div><button type="button" class="calendar-arrow" data-calendar="next" aria-label="下一${view==='days'?'个月':view==='years'?'组年份':'年'}">›</button></div>`;
    let body = '';
    if (view === 'years') {
      const start = Math.max(MIN_YEAR,Math.min(MAX_YEAR-11,state.yearPage));
      body = `<div class="calendar-range">${start}—${start+11}</div><div class="calendar-choices">${Array.from({length:12},(_,n)=>start+n).map(y=>`<button type="button" data-calendar="year" data-year="${y}" class="${year===y?'selected':''}" aria-pressed="${year===y}">${y}年</button>`).join('')}</div>`;
    } else if (view === 'months') {
      body = `<div class="calendar-choices">${Array.from({length:12},(_,m)=>`<button type="button" data-calendar="month" data-month="${m}" class="${month===m?'selected':''}" aria-pressed="${month===m}">${m+1}月</button>`).join('')}</div>`;
    } else {
      const first = new Date(year,month,1,12), offset = (first.getDay()+6)%7;
      const cells = Array.from({length:42},(_,n)=>new Date(year,month,1-offset+n,12));
      body = `<div class="calendar-week" aria-hidden="true">${['一','二','三','四','五','六','日'].map(d=>`<span>${d}</span>`).join('')}</div><div class="calendar-days" role="group" aria-label="${year}年${month+1}月">${cells.map(d=>{
        const value=key(d), selected=value===state.selected, isToday=value===today();
        const disabled=d.getFullYear()<MIN_YEAR||d.getFullYear()>MAX_YEAR;
        return `<button type="button" class="calendar-day ${d.getMonth()!==month?'outside ':''}${selected?'selected ':''}${isToday?'today':''}" data-calendar="day" data-date="${value}" aria-label="${value}" aria-pressed="${selected}" ${isToday?'aria-current="date"':''} ${disabled?'disabled':''}>${d.getDate()}</button>`;
      }).join('')}</div>`;
    }
    document.getElementById('calendar-content').innerHTML = header+body+`<div class="calendar-shortcuts"><button type="button" data-calendar="today">今天</button>${state.required?'':'<button type="button" data-calendar="clear">清除日期</button>'}</div>`;
    document.getElementById('calendar-selection').textContent = display(state.selected);
    root.querySelector('[data-calendar="confirm"]').disabled = state.required && !state.selected;
    if (focusDate) root.querySelector(`[data-date="${focusDate}"]`)?.focus({preventScroll:true});
  }
  function close() {
    if (!root.open) return;
    const trigger = state?.trigger;
    root.close(); state = null;
    if (trigger?.isConnected) trigger.focus({preventScroll:true});
  }
  function open({value='',label='选择日期',required=false,onChange,trigger=null}) {
    if (typeof onChange !== 'function') throw new TypeError('日期选择缺少保存回调。');
    const original=parse(value), current=original||parse(today());
    const year=Math.max(MIN_YEAR,Math.min(MAX_YEAR,current.getFullYear()));
    state={year,month:current.getMonth(),selected:original?value:'',view:'days',yearPage:year-5,required,onChange,trigger};
    document.activeElement?.blur();
    document.getElementById('calendar-title').textContent=label;
    render();
    if (!root.open) root.showModal();
    (root.querySelector(`[data-date="${state.selected||today()}"]`)||root.querySelector('[data-calendar="cancel"]')).focus({preventScroll:true});
  }
  root.addEventListener('click',event=>{
    const button=event.target.closest('[data-calendar]');
    if (!button || button.disabled || !state) return;
    const action=button.dataset.calendar;
    if(action==='cancel'){close();return;}
    if(action==='confirm'){
      const {selected,required,onChange}=state;
      if(required&&!selected)return;
      close();onChange(selected);return;
    }
    if(action==='day'){
      const d=parse(button.dataset.date);state.selected=key(d);state.year=d.getFullYear();state.month=d.getMonth();render(state.selected);return;
    }
    if(action==='today'){
      const d=parse(today());state.selected=today();state.year=d.getFullYear();state.month=d.getMonth();state.view='days';render(state.selected);return;
    }
    if(action==='clear'){state.selected='';render();return;}
    if(action==='years'){state.view=state.view==='years'?'days':'years';state.yearPage=state.year-5;}
    if(action==='months')state.view=state.view==='months'?'days':'months';
    if(action==='year'){state.year=Number(button.dataset.year);state.view='months';}
    if(action==='month'){state.month=Number(button.dataset.month);state.view='days';}
    if(action==='prev'||action==='next'){
      const step=action==='prev'?-1:1;
      if(state.view==='years')state.yearPage=Math.max(MIN_YEAR,Math.min(MAX_YEAR-11,state.yearPage+step*12));
      else if(state.view==='months')state.year=Math.max(MIN_YEAR,Math.min(MAX_YEAR,state.year+step));
      else{const d=new Date(state.year,state.month+step,1,12);if(d.getFullYear()>=MIN_YEAR&&d.getFullYear()<=MAX_YEAR){state.year=d.getFullYear();state.month=d.getMonth();}}
    }
    render();
  });
  root.addEventListener('cancel',event=>{event.preventDefault();close();});
  root.addEventListener('click',event=>{if(event.target!==root)return;const r=root.getBoundingClientRect();if(event.clientX<r.left||event.clientX>r.right||event.clientY<r.top||event.clientY>r.bottom)close();});
  root.addEventListener('keydown',event=>{
    const button=event.target.closest('[data-date]');
    if(!button||!state||state.view!=='days')return;
    const shift={ArrowLeft:-1,ArrowRight:1,ArrowUp:-7,ArrowDown:7}[event.key];
    if(shift===undefined)return;
    event.preventDefault();const d=parse(button.dataset.date);d.setDate(d.getDate()+shift);
    if(d.getFullYear()<MIN_YEAR||d.getFullYear()>MAX_YEAR)return;
    state.year=d.getFullYear();state.month=d.getMonth();render(key(d));
  });
  return Object.freeze({open,close,display,get isOpen(){return root.open;}});
})();
