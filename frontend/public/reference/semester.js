/* Semester planning UI. All catalog records and recommendations are a local demo. */
(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const weekdays = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri'];
  let ready = false, searchOpen = false, searchMode = 'all', contextId = null;
  let preview = null, pendingAdd = null, returnFocus = null;
  const S = () => window.Syllo;
  const esc = value => S().escape(String(value ?? ''));
  const course = id => S().catalog[id];
  const state = () => S().state;
  const credits = ids => ids.reduce((total, id) => total + (course(id)?.credits || 0), 0);
  const termName = () => S().terms.find(term => term.id === S().nextTerm)?.label || 'Spring 2027';
  const formatTime = minutes => `${Math.floor(minutes / 60) % 12 || 12}:${String(minutes % 60).padStart(2, '0')} ${minutes >= 720 ? 'PM' : 'AM'}`;
  const instructor = c => c.section?.split('·').slice(1).join('·').trim() || 'Instructor unavailable';
  const planned = id => state().planner.includes(id);
  const eligible = id => S().eligible(id, S().nextTerm);
  const defaults = { start: 540, end: 1020, dayOff: 'Fri', professor: '', minCredits: 12, maxCredits: 16, seeds: [], aiOpen: false };

  function preferences() {
    state().preferences = { ...defaults, ...state().preferences };
    if (!Array.isArray(state().preferences.seeds)) state().preferences.seeds = [];
    if (!state().preferences.unifiedCourseSelection) {
      state().preferences.seeds = [...state().planner];
      state().preferences.unifiedCourseSelection = true;
    }
    return state().preferences;
  }
  function persistUI() { S().persist(); }
  function notify(title, detail = '') { S().showToast(title, detail); }
  function pairConflicts(ids) { return S().conflicts(ids); }
  function clashes(id, ids = state().planner) {
    return pairConflicts([...new Set([...ids, id])]).filter(pair => pair.includes(id)).map(pair => pair.find(other => other !== id));
  }
  function setSearch(open) {
    searchOpen = open;
    $('course-search-results').classList.toggle('is-open', open);
    $('course-search').setAttribute('aria-expanded', String(open));
  }
  function prerequisites(c) {
    return c.prereqs?.length ? c.prereqs.map(id => course(id)?.code || id).join(', ') : 'None in this demo catalog';
  }
  function offering(c) { return (c.terms || []).map(season => season[0].toUpperCase() + season.slice(1)).join(' / ') || 'Not provided'; }
  function detailMarkup(c) {
    return `<details><summary>Course details & prerequisites</summary><div class="search-detail-grid">
      <div class="sem-detail-wide"><span>Description</span><strong>${esc(c.description || 'Description unavailable')}</strong></div>
      <div><span>Prerequisites</span><strong>${esc(prerequisites(c))}</strong></div><div><span>Usually offered · demo</span><strong>${esc(offering(c))}</strong></div>
      <div><span>Requirement</span><strong>${esc(c.requirement)}</strong></div><div><span>Credits</span><strong>${c.credits}</strong></div>
      <div><span>Section & instructor</span><strong>${esc(c.section || 'Unavailable')}</strong></div><div><span>Professor rating · demo</span><strong>${c.rating ? esc(c.rating) + ' / 5' : 'Not available'}</strong></div>
      <div><span>Meetings</span><strong>${esc(c.days)} · ${esc(c.time)}</strong></div><div><span>Registration</span><strong>Demo section · no live seat data</strong></div>
      </div></details>`;
  }
  function renderSearch() {
    const query = $('course-search').value.trim().toLowerCase();
    const tokens = query.split(/\s+/).filter(Boolean);
    const matches = Object.entries(S().catalog).filter(([id, c]) => {
      if (searchMode === 'electives' && c.required) return false;
      const text = [c.code, c.name, c.description, c.requirement, c.section, c.interest].join(' ').toLowerCase();
      return tokens.every(token => text.includes(token));
    }).sort(([a], [b]) => Number(eligible(b).ok) - Number(eligible(a).ok));
    $('course-search-results').innerHTML = `<div class="sem-search-summary">${matches.length} ${searchMode === 'electives' ? 'elective' : 'course'} result${matches.length === 1 ? '' : 's'} · ${esc(termName())}<button type="button" data-sem-action="close-search" aria-label="Close course results">×</button></div>` +
      (matches.length ? matches.map(([id, c]) => {
        const access = eligible(id), added = planned(id), conflicts = clashes(id);
        return `<article class="search-result ${access.ok ? '' : 'sem-blocked'}" draggable="${access.ok && !added}" ${access.ok && !added ? `data-sem-drag="${id}"` : ''}>
          <div class="search-result-main"><div><span>${esc(c.code)}</span><strong>${esc(c.name)}</strong><small>${c.credits} credits · ${esc(c.days)} · ${esc(c.time)}</small></div>
          <button class="button ${added || !access.ok ? 'quiet' : 'primary'}" type="button" data-sem-action="add" data-id="${id}" ${added || !access.ok ? 'disabled' : ''}>${added ? 'Added' : access.ok ? 'Add to Schedule' : 'Preview only'}</button></div>
          ${!access.ok ? `<p class="course-warning sem-prerequisite">${access.reasons.map(esc).join(' ')}${c.prereqs?.length ? ` Take ${esc(prerequisites(c))} first, then revisit this course in a later ${esc(offering(c).toLowerCase())} term.` : ''}</p>` : ''}
          ${access.ok && !added && conflicts.length ? `<p class="course-warning conflict">Overlaps ${conflicts.map(other => esc(course(other).code)).join(', ')}. You can choose which to keep.</p>` : ''}
          ${detailMarkup(c)}</article>`;
      }).join('') : '<div class="empty-state">No matches. Try a course code, department, or a different keyword.</div>');
    setSearch(searchOpen);
  }
  function sidebarItem(id, type) {
    const c = course(id), added = planned(id);
    return `<article class="planner-sidebar-course ${type === 'planned' ? 'planned' : ''}" ${type !== 'planned' && !added ? `draggable="true" data-sem-drag="${id}"` : ''}>
      <span class="sidebar-course-color" style="background:${c.color}"></span><div><button class="sem-course-link" type="button" data-sem-action="details" data-id="${id}" title="${esc(c.name)} · Open course details"><strong>${esc(c.code)}</strong><span>${esc(c.name)}</span></button></div>
      <div class="sem-card-actions">${type === 'planned' ? `<button type="button" data-sem-action="remove" data-id="${id}" aria-label="Remove ${esc(c.code)}">×</button>` : `<button class="course-add-button" type="button" data-sem-action="add" data-id="${id}" aria-label="${added?'Added':'Add'} ${esc(c.code)}" title="${added?'On your schedule and included in AI planning':'Add to your schedule and include in AI planning'}" ${added?'disabled':''}>${added?'Added':'Add'}</button>`}</div></article>`;
  }
  function renderSidebar() {
    const recommendations = S().recommendations();
    $('required-course-count').textContent = recommendations.length;
    $('planned-course-count').textContent = state().planner.length;
    $('required-course-list').innerHTML = recommendations.length ? recommendations.map(id => sidebarItem(id, 'recommended')).join('') : '<div class="sidebar-empty">No eligible required courses in the demo catalog this term.</div>';
    $('planned-course-list').innerHTML = state().planner.length ? state().planner.map(id => sidebarItem(id, 'planned')).join('') : '<div class="sidebar-empty">Your calendar is ready. Add a course above.</div>';
  }
  function renderCalendar() {
    const ids = state().planner;
    $('planner-credit-total').textContent = credits(ids);
    const pairs = pairConflicts(ids), conflictIds = new Set(pairs.flat());
    const holder = $('planner-calendar-events');
    holder.innerHTML = ids.length ? ids.flatMap(id => {
      const c = course(id), range = S().timeRange(id);
      if (!range) return [];
      const [start, end] = range;
      const top = Math.max(0, (start - 480) / 540 * 100), height = Math.min(100 - top, (end - start) / 540 * 100);
      return S().days(id).filter(day => weekdays.includes(day)).map(day => {
        const sameDayOverlap = ids.filter(other => other !== id && S().days(other).includes(day) && clashes(id, [other]).includes(other));
        const overlapGroup = [id, ...sameDayOverlap].sort(), lane = overlapGroup.indexOf(id), lanes = overlapGroup.length;
        return `<article class="planner-calendar-event ${conflictIds.has(id) ? 'has-conflict' : ''}" draggable="false" style="--day:${weekdays.indexOf(day) + 1};--top:${top}%;--height:${height}%;--event-color:${c.color};--event-bg:${c.bg};--sem-lane:${lane};--sem-lanes:${lanes}" data-sem-course="${id}" tabindex="0" role="button" aria-label="${esc(c.code)}, ${day}, ${formatTime(start)} to ${formatTime(end)}. Open course details; right-click for options." title="${esc(c.name)} · ${esc(c.time)}${conflictIds.has(id) ? ' · Time conflict' : ''}">
        <button type="button" data-sem-action="remove" data-id="${id}" aria-label="Remove ${esc(c.code)}">×</button><strong>${esc(c.code)}</strong><span>${esc(c.name)}</span><small>${formatTime(start)}–${formatTime(end)}</small>${conflictIds.has(id) ? '<b class="sem-event-warning">Overlap</b>' : ''}</article>`;
      });
    }).join('') : '<p class="planner-calendar-empty">Add a course or drop one here</p>';
    $('sem-conflict-notice').innerHTML = pairs.length ? `<span>${pairs.length} time conflict${pairs.length > 1 ? 's' : ''} in this plan.</span> <button type="button" data-sem-action="resolve-existing">Review conflicts</button>` : '';
    $('sem-conflict-notice').hidden = !pairs.length;
    if($('sem-full-calendar')?.open)renderFullCalendar();
  }
  function renderFullCalendar(){
    const allDays=['Mon','Tue','Wed','Thu','Fri','Sat','Sun'],ids=state().planner;
    $('sem-full-events').innerHTML=ids.flatMap(id=>{
      const c=course(id),range=S().timeRange(id);if(!range)return [];
      return S().days(id).filter(day=>allDays.includes(day)).map(day=>{
        const group=[id,...ids.filter(other=>other!==id&&S().days(other).includes(day)&&clashes(id,[other]).includes(other))].sort();
        return `<button type="button" class="sem-full-event" data-full-course="${id}" style="--full-day:${allDays.indexOf(day)};--full-lane:${group.indexOf(id)};--full-lanes:${group.length};top:${range[0]/1440*100}%;height:${(range[1]-range[0])/1440*100}%;--event-color:${c.color};--event-bg:${c.bg}" aria-label="${esc(c.code)}, ${day}, ${esc(c.time)}. View details"><strong>${esc(c.code)}</strong><span>${esc(c.name)}</span><small>${formatTime(range[0])}–${formatTime(range[1])}</small></button>`;
      });
    }).join('');
  }
  function openFullCalendar(){
    closeContext();setSearch(false);renderFullCalendar();$('sem-full-calendar').showModal();
    $('sem-full-scroll').scrollTop=7*56;
  }
  function renderChat() {
    const chat = $('planner-chat');
    const messages = Array.isArray(state().chat) ? state().chat : [];
    chat.innerHTML = messages.length ? messages.map(message => `<div class="chat-message ${message.role === 'user' ? 'user' : 'assistant'}"><span>${message.role === 'user' ? 'You' : 'Syllo'}</span><p>${esc(message.text ?? message.content ?? '')}</p></div>`).join('') : '<div class="chat-message assistant"><span>Syllo</span><p>What would make your week better? Tell me your preferences, and I’ll suggest a plan you can review.</p></div>';
    chat.scrollTop = chat.scrollHeight;
    $('planner-chat-context').textContent = `${state().planner.length} selected courses · ${credits(state().planner)} credits`;
    $('planner-chat-input').value = state().draft || '';
  }
  function appendMessage(role, text) {
    if (!Array.isArray(state().chat)) state().chat = [];
    state().chat.push({ role, text });
    persistUI(); renderChat();
  }
  function setAi(open) {
    preferences().aiOpen = open;
    $('syllo-ai-toggle').setAttribute('aria-expanded', String(open));
    $('syllo-ai-toggle').querySelector('.syllo-ai-label').textContent = open ? 'Close AI' : 'Syllo AI';
    $('syllo-ai-panel').classList.toggle('is-collapsed', !open);
    $('syllo-ai-panel').setAttribute('aria-hidden', String(!open));
    $('syllo-ai-panel').inert = !open;
    document.querySelector('.planner-layout').classList.toggle('ai-closed', !open);
    persistUI();
  }
  function renderPreferences() {
    const p = preferences();
    ['start', 'end', 'dayOff', 'professor', 'minCredits', 'maxCredits'].forEach(key => { const input = document.querySelector(`[data-sem-pref="${key}"]`); if (input && document.activeElement !== input) input.value = p[key]; });
    setAi(p.aiOpen);
  }
  function updateCountdown() {
    const target = new Date('2026-11-02T07:00:00-05:00');
    const left = target - new Date();
    const days = Math.max(0, Math.ceil(left / 86400000));
    $('sem-countdown-number').textContent = left > 0 ? `${days} ${days===1?'day':'days'}` : 'Open';
    document.querySelector('.sem-registration b').textContent = left > 0 ? 'Until registration opens' : 'Registration is open';
    $('sem-countdown-detail').textContent = 'Nov 2, 2026 · 7:00 AM ET · Demo date';
  }
  function render() {
    if (!ready) return;
    renderSearch(); renderCalendar(); renderSidebar(); renderChat(); renderPreferences();
    updateCountdown();
    if (preview) renderPreview();
  }

  function openDialog(title, content, options = {}) {
    returnFocus = document.activeElement;
    $('sem-dialog-title').textContent = title;
    $('sem-dialog-body').innerHTML = content;
    const dialog = $('sem-dialog');
    dialog.dataset.kind = options.kind || '';
    if (!dialog.open) dialog.showModal();
    requestAnimationFrame(() => dialog.querySelector('input, button:not(.sem-dialog-x), select')?.focus());
  }
  function closeDialog() { $('sem-dialog').close(); pendingAdd = null; returnFocus?.focus?.(); }
  function add(id, strategy) {
    if (!course(id) || planned(id)) return;
    const access = eligible(id);
    if (!access.ok) { notify('Course cannot be added', access.reasons.join(' ')); return; }
    const conflicts = clashes(id);
    if (conflicts.length && !strategy) {
      pendingAdd = id;
      openDialog('Choose which courses to keep', `<p class="sem-dialog-copy">${esc(course(id).code)} overlaps ${conflicts.map(other => esc(course(other).code)).join(', ')}. Choose how to update your plan.</p><div class="sem-conflict-comparison">${[id, ...conflicts].map(other => `<article><strong>${esc(course(other).code)} · ${esc(course(other).name)}</strong><span>${esc(course(other).days)} · ${esc(course(other).time)}</span><small>${other === id ? 'New course' : 'Already on your calendar'}</small></article>`).join('')}</div><div class="sem-dialog-actions"><button class="button quiet" data-sem-action="close-dialog">Keep current courses</button><button class="button primary" data-sem-action="replace-conflict" data-id="${id}">Use ${esc(course(id).code)}</button></div><button class="text-button sem-keep-both" data-sem-action="keep-conflict" data-id="${id}">Keep both and mark the conflict</button>`, { kind: 'conflict' });
      return;
    }
    S().change(`Add ${course(id).code}`, current => {
      if (strategy === 'replace') current.planner = current.planner.filter(other => !conflicts.includes(other));
      current.planner = [...current.planner, id];
      current.preferences.seeds = [...current.planner];
    });
    setSearch(false); preview = null; renderPreview();
    notify(`${course(id).code} added`, strategy === 'both' ? 'The overlap remains highlighted. You can undo this change.' : 'Your calendar and planned classes are updated.');
  }
  function remove(id) {
    if (!planned(id)) return;
    S().change(`Remove ${course(id).code}`, current => { current.planner = current.planner.filter(other => other !== id); current.preferences.seeds = [...current.planner]; });
    closeContext(); preview = null; renderPreview();
    notify(`${course(id).code} removed`, 'Use Undo or Ctrl/⌘ + Z to restore it.');
  }
  function openContext(event, id) {
    event.preventDefault(); contextId = id;
    const menu = $('calendar-context-menu'), c = course(id);
    menu.innerHTML = `<div class="sem-context-summary"><strong>${esc(c.code)}</strong><span>${esc(instructor(c))}</span><small>${c.rating ? `★ ${esc(c.rating)} / 5 · demo rating` : 'Rating unavailable'}</small></div><button role="menuitem" data-sem-action="details" data-id="${id}"><span>Course & professor details</span><small>Prerequisites, workload, and meetings</small></button><button class="danger" role="menuitem" data-sem-action="remove" data-id="${id}"><span>Remove course</span><small>Delete from this plan</small></button>`;
    menu.classList.remove('hidden');
    const rect = menu.getBoundingClientRect();
    menu.style.left = `${Math.max(8, Math.min(event.clientX || rect.width, window.innerWidth - rect.width - 12))}px`;
    menu.style.top = `${Math.max(8, Math.min(event.clientY || 100, window.innerHeight - rect.height - 12))}px`;
    menu.querySelector('button')?.focus();
  }
  function closeContext() { $('calendar-context-menu').classList.add('hidden'); contextId = null; }
  function preferenceIssues(id, p = preferences()) {
    const c = course(id), range = S().timeRange(id), issues = [];
    if (range && range[0] < Number(p.start)) issues.push(`${c.code} starts at ${formatTime(range[0])}, before your ${formatTime(Number(p.start))} preference.`);
    if (range && range[1] > Number(p.end)) issues.push(`${c.code} ends at ${formatTime(range[1])}, after your ${formatTime(Number(p.end))} preference.`);
    if (p.dayOff && S().days(id).includes(p.dayOff)) issues.push(`${c.code} meets on your preferred day off (${p.dayOff}).`);
    if (p.professor && !instructor(c).toLowerCase().includes(p.professor.toLowerCase())) issues.push(`${c.code} has ${instructor(c)}; your preferred professor is not offered for its demo section.`);
    return issues;
  }
  function generatePreview() {
    const p = preferences();
    if (Number(p.start) >= Number(p.end)) { notify('Check your time window', 'The preferred end time must be after the start time.'); return null; }
    if (Number(p.minCredits) > Number(p.maxCredits) || Number(p.minCredits) < 1 || Number(p.maxCredits) > 24) { notify('Check your credit range', 'Use a minimum and maximum from 1 to 24, with the minimum first.'); return null; }
    const selected = [...new Set([...state().planner, ...p.seeds])].filter(id => course(id) && eligible(id).ok);
    const candidates = S().recommendations().filter(id => !selected.includes(id)).sort((a, b) => {
      const aIssues = preferenceIssues(a, p).length, bIssues = preferenceIssues(b, p).length;
      return aIssues - bIssues || Number(course(b).rating || 0) - Number(course(a).rating || 0);
    });
    const chosen = [...selected], skipped = [];
    for (const id of candidates) {
      if (credits(chosen) >= Number(p.minCredits)) break;
      if (credits(chosen) + course(id).credits > Number(p.maxCredits)) { skipped.push(id); continue; }
      if (clashes(id, chosen).length) { skipped.push(id); continue; }
      chosen.push(id);
    }
    // Eligible electives can fill a small credit gap; degree requirements remain the first choice.
    if (credits(chosen) < Number(p.minCredits)) {
      const electives = Object.keys(S().catalog).filter(id => !course(id).required && !chosen.includes(id) && eligible(id).ok).sort((a, b) => preferenceIssues(a, p).length - preferenceIssues(b, p).length);
      for (const id of electives) {
        if (credits(chosen) >= Number(p.minCredits)) break;
        if (credits(chosen) + course(id).credits <= Number(p.maxCredits) && !clashes(id, chosen).length) chosen.push(id);
      }
    }
    const exceptions = chosen.flatMap(id => preferenceIssues(id, p));
    if (credits(chosen) < Number(p.minCredits)) exceptions.push(`Only ${credits(chosen)} credits fit the available demo sections. Your minimum is ${p.minCredits}.`);
    if (credits(chosen) > Number(p.maxCredits)) exceptions.push(`Your chosen courses total ${credits(chosen)} credits, above your ${p.maxCredits}-credit maximum. Uncheck or remove a course before applying.`);
    preview = { ids: chosen, reasons: {}, exceptions, skipped, conflicts: pairConflicts(chosen), source: [...state().planner], max: Number(p.maxCredits) };
    chosen.forEach(id => { preview.reasons[id] = state().planner.includes(id) ? 'Kept from your current schedule.' : p.seeds.includes(id) ? 'Included because you chose it.' : course(id).required ? 'Advances a remaining requirement; prerequisites are met and it is offered next semester.' : 'Eligible elective that helps reach your credit goal.'; });
    appendMessage('assistant', `I prepared ${chosen.length} courses (${credits(chosen)} credits). ${exceptions.length ? `${exceptions.length} preference exception${exceptions.length === 1 ? '' : 's'} need your review.` : 'The plan fits your time, day, and credit preferences.'} Review the preview below before applying.`);
    renderPreview();
    $('sem-ai-preview').scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    return preview;
  }
  function renderPreview() {
    const holder = $('sem-ai-preview');
    if (!preview) { holder.innerHTML = ''; holder.hidden = true; return; }
    preview.conflicts = pairConflicts(preview.ids);
    holder.hidden = false;
    const tooMany = credits(preview.ids) > preview.max;
    holder.innerHTML = `<div class="sem-preview-head"><strong>Plan preview</strong><span>${credits(preview.ids)} credits</span></div><p>Your calendar changes only after you apply this plan.</p><div class="sem-preview-courses">${preview.ids.map(id => `<div><strong>${esc(course(id).code)}</strong><span>${esc(preview.reasons[id])}</span><small>${esc(course(id).days)} · ${esc(course(id).time)}</small></div>`).join('')}</div>
      ${preview.exceptions.length ? `<details class="sem-preview-exceptions" open><summary>Preferences to review</summary><ul>${preview.exceptions.map(reason => `<li>${esc(reason)}</li>`).join('')}</ul></details>` : '<p class="sem-preview-success">Fits your selected preferences.</p>'}
      ${preview.conflicts.length ? `<div class="sem-preview-conflicts"><strong>Choose between overlapping courses</strong>${preview.conflicts.map(([a, b]) => `<div><p>${esc(course(a).code)} and ${esc(course(b).code)} overlap.</p><button class="button quiet" data-sem-action="preview-keep" data-keep="${a}" data-drop="${b}">Keep ${esc(course(a).code)}</button><button class="button quiet" data-sem-action="preview-keep" data-keep="${b}" data-drop="${a}">Keep ${esc(course(b).code)}</button></div>`).join('')}</div>` : ''}
      <div class="sem-preview-actions"><button class="button quiet" data-sem-action="cancel-preview">Cancel</button><button class="button primary" data-sem-action="apply-preview" ${preview.conflicts.length || tooMany || !preview.ids.length ? 'disabled' : ''}>Apply plan</button></div>`;
  }
  function applyPreview() {
    if (!preview || preview.conflicts.length || credits(preview.ids) > preview.max) return;
    if (JSON.stringify(state().planner) !== JSON.stringify(preview.source)) { notify('Your schedule changed', 'Generate a fresh preview before applying.'); preview = null; renderPreview(); return; }
    const ids = [...preview.ids], invalid = ids.filter(id => !eligible(id).ok);
    if (invalid.length) { notify('Eligibility changed', 'Generate a fresh preview to check prerequisites again.'); return; }
    S().change('Apply Syllo plan', current => { current.planner = ids; current.preferences.seeds = [...ids]; });
    preview = null; renderPreview(); appendMessage('assistant', 'Your reviewed plan is now on the calendar. You can adjust any course manually or undo this entire change.');
    notify('Plan applied', `${ids.length} courses · ${credits(ids)} credits. Save it whenever you are ready.`);
  }
  function sendPrompt(raw) {
    const text = raw.trim(); if (!text) return;
    state().draft = ''; appendMessage('user', text);
    const p = preferences(), lower = text.toLowerCase();
    const dayMap = { monday: 'Mon', tuesday: 'Tue', wednesday: 'Wed', thursday: 'Thu', friday: 'Fri' };
    let understood = false;
    Object.entries(dayMap).forEach(([day, value]) => { if (lower.includes(day) && /free|off|no class|avoid/.test(lower)) { p.dayOff = value; understood = true; } });
    if (/early|morning/.test(lower) && /avoid|no|later|after/.test(lower)) { p.start = 600; understood = true; }
    const startMatch = lower.match(/(?:start|after|before\s+no classes)\D{0,12}(\d{1,2})(?::(\d{2}))?\s*(am|pm)/);
    if (startMatch) { let hour = Number(startMatch[1]) % 12; if (startMatch[3] === 'pm') hour += 12; p.start = hour * 60 + Number(startMatch[2] || 0); understood = true; }
    const endMatch = lower.match(/(?:finish|end|done)\D{0,12}(\d{1,2})(?::(\d{2}))?\s*(am|pm)/);
    if (endMatch) { let hour = Number(endMatch[1]) % 12; if (endMatch[3] === 'pm') hour += 12; p.end = hour * 60 + Number(endMatch[2] || 0); understood = true; }
    const range = lower.match(/(\d{1,2})\s*(?:-|to|–)\s*(\d{1,2})\s*credits/);
    if (range) { p.minCredits = Number(range[1]); p.maxCredits = Number(range[2]); understood = true; }
    Object.entries(S().catalog).forEach(([id, c]) => { if (lower.includes(c.code.toLowerCase()) && eligible(id).ok) { p.seeds = [...new Set([...p.seeds, id])]; understood = true; } });
    persistUI(); renderPreferences(); renderSidebar();
    if (understood || /generate|build|plan|suggest|workload|degree|schedule/.test(lower)) generatePreview();
    else appendMessage('assistant', 'This demo understands course codes, a day off, “start after 10 AM,” “finish by 4 PM,” and a credit range such as “12 to 16 credits.” You can also set these in Planning preferences and preview a plan.');
  }
  function save() {
    if (!state().planner.length) { notify('Add a course first', 'An empty calendar has nothing to save.'); return; }
    openDialog('Save this schedule', `<label class="sem-save-label">Schedule name<input id="sem-save-name" maxlength="80" value="${esc(termName())} · My plan"></label><p class="sem-dialog-copy">${state().planner.length} courses · ${credits(state().planner)} credits. Saved in this browser for you to reopen later.</p><div class="sem-dialog-actions"><button class="button quiet" data-sem-action="close-dialog">Cancel</button><button class="button primary" data-sem-action="confirm-save">Save schedule</button></div>`, { kind: 'save' });
  }
  function readPreference(input) {
    const key = input.dataset.semPref;
    preferences()[key] = ['start', 'end', 'minCredits', 'maxCredits'].includes(key) ? Number(input.value) : input.value;
    preview = null; persistUI(); renderPreview();
  }
  function onClick(event) {
    const button = event.target.closest('[data-sem-action]');
    if (!button) {
      const calendarCourse = event.target.closest('[data-sem-course]');
      if (calendarCourse && !event.target.closest('button')) S().openCourse(calendarCourse.dataset.semCourse);
      return;
    }
    const id = button.dataset.id;
    switch (button.dataset.semAction) {
      case 'toggle-ai': setAi(!preferences().aiOpen); break;
      case 'expand-calendar': openFullCalendar(); break;
      case 'search': renderSearch(); setSearch(true); $('course-search').focus(); break;
      case 'close-search': setSearch(false); $('course-search').focus(); break;
      case 'add': add(id); break;
      case 'remove': remove(id); break;
      case 'details': closeContext(); S().openCourse(id); break;
      case 'undo': preview = null; S().undo(); renderPreview(); break;
      case 'save': save(); break;
      case 'confirm-save': {
        const name = $('sem-save-name').value.trim();
        if (!name) { $('sem-save-name').focus(); return; }
        S().saveSchedule(name); closeDialog(); break;
      }
      case 'close-dialog': closeDialog(); break;
      case 'replace-conflict': closeDialog(); add(id, 'replace'); break;
      case 'keep-conflict': closeDialog(); add(id, 'both'); break;
      case 'resolve-existing': {
        const pairs = pairConflicts(state().planner);
        openDialog('Resolve schedule overlaps', `<p class="sem-dialog-copy">Choose one course from each overlap to remove. You can undo changes.</p>${pairs.map(([a, b]) => `<div class="sem-conflict-row"><strong>${esc(course(a).code)} + ${esc(course(b).code)}</strong><div><button class="button quiet" data-sem-action="resolve-remove" data-id="${a}">Remove ${esc(course(a).code)}</button><button class="button quiet" data-sem-action="resolve-remove" data-id="${b}">Remove ${esc(course(b).code)}</button></div></div>`).join('')}<div class="sem-dialog-actions"><button class="button quiet" data-sem-action="close-dialog">Close</button></div>`); break;
      }
      case 'resolve-remove': remove(id); closeDialog(); break;
      case 'generate': generatePreview(); break;
      case 'apply-preview': applyPreview(); break;
      case 'cancel-preview': preview = null; renderPreview(); break;
      case 'preview-keep': {
        if (!preview) break;
        const removedCode = course(button.dataset.drop).code;
        preview.ids = preview.ids.filter(id => id !== button.dataset.drop);
        preview.exceptions = preview.exceptions.filter(reason => !reason.startsWith(removedCode) && !reason.startsWith('Only ') && !reason.startsWith('Your chosen'));
        if (credits(preview.ids) < Number(preferences().minCredits)) preview.exceptions.push(`Only ${credits(preview.ids)} credits remain after your conflict choice; your minimum is ${preferences().minCredits}.`);
        renderPreview(); break;
      }
      case 'quick-prompt': sendPrompt(button.dataset.prompt); break;
    }
  }
  function init() {
    if (ready) return;
    const root = document.querySelector('[data-screen="planner"]'); if (!root) return;
    ready = true;
    root.querySelector('.planner-header .features-kicker').textContent = `${termName().toUpperCase()} · NEXT SEMESTER`;
    root.querySelector('.planner-header h1').textContent = 'Semester Planner';
    root.querySelector('.planner-header p').textContent = 'Build your next semester, one course at a time.';
    root.querySelector('.planner-header').insertAdjacentHTML('afterend', `<div class="sem-toolbar"><div class="sem-registration"><strong id="sem-countdown-number"></strong><span><b>Until registration</b><small id="sem-countdown-detail"></small></span></div><div class="sem-toolbar-actions"><button class="button quiet" type="button" data-sem-action="undo" title="Undo last planning change (Ctrl or Command + Z)">↶ Undo</button><button class="button primary" type="button" data-sem-action="save">Save schedule</button></div></div>`);
    root.querySelector('.catalog-note').textContent = 'Demo catalog · sections, prerequisites, ratings, and offerings are simulated.';
    $('course-search').insertAdjacentHTML('afterend', '<button class="sem-search-button" type="button" data-sem-action="search">Search</button>');
    root.querySelector('.catalog-note').insertAdjacentHTML('afterend', '<div class="sem-search-controls"><label><span>Explore</span><select id="sem-search-mode"><option value="all">All courses</option><option value="electives">Electives & prerequisites</option></select></label><span>Unavailable courses can be previewed.</span></div>');
    root.querySelector('.planner-course-sidebar section>p').textContent = 'Needed for your demo degree, prerequisites met, and offered next semester. Drag to your calendar or use +.';
    root.querySelector('.manual-schedule-heading').insertAdjacentHTML('afterend', '<div class="sem-conflict-notice" id="sem-conflict-notice" role="status" hidden></div>');
    root.querySelector('.manual-schedule-heading').insertAdjacentHTML('beforeend','<button class="button quiet sem-expand-calendar" type="button" data-sem-action="expand-calendar">View bigger <span aria-hidden="true">↗</span></button>');
    document.body.insertAdjacentHTML('beforeend',`<dialog class="sem-full-calendar" id="sem-full-calendar" aria-labelledby="sem-full-title"><header class="sem-full-header"><div><h2 id="sem-full-title">Full weekly calendar</h2><p>Monday–Sunday · All 24 hours · Scroll to see earlier or later times</p></div><button class="button quiet" type="button" id="sem-full-close" aria-label="Close expanded calendar">Close ×</button></header><div class="sem-full-scroll" id="sem-full-scroll"><div class="sem-full-week"><div class="sem-full-days"><span></span>${['Monday','Tuesday','Wednesday','Thursday','Friday','Saturday','Sunday'].map(day=>`<strong>${day}</strong>`).join('')}</div><div class="sem-full-grid"><div class="sem-full-times">${Array.from({length:25},(_,hour)=>`<span style="top:${hour/24*100}%">${formatTime(hour*60)}</span>`).join('')}</div><div class="sem-full-lines"></div><div class="sem-full-events" id="sem-full-events"></div></div></div></div></dialog>`);
    $('sem-full-close').addEventListener('click',()=>$('sem-full-calendar').close());
    const fullCourseDetails=event=>{const item=event.target.closest('[data-full-course]');if(!item)return;event.preventDefault();$('sem-full-calendar').close();S().openCourse(item.dataset.fullCourse)};
    $('sem-full-calendar').addEventListener('click',fullCourseDetails);
    $('sem-full-calendar').addEventListener('contextmenu',fullCourseDetails);
    $('syllo-ai-toggle').removeAttribute('data-action'); $('syllo-ai-toggle').dataset.semAction = 'toggle-ai';
    const professors = [...new Set(Object.values(S().catalog).filter(c => c.terms?.includes('spring')).map(instructor))];
    const timeOptions = [480, 510, 540, 570, 600, 630, 660, 720, 780, 840, 900, 960, 1020, 1080].map(n => `<option value="${n}">${formatTime(n)}</option>`).join('');
    root.querySelector('.intelligence-heading').insertAdjacentHTML('afterend', `<details class="sem-ai-preferences" open><summary>Planning preferences</summary><p>Pick courses in Recommended classes, then set your week.</p><div class="sem-preference-grid"><label>Start after<select data-sem-pref="start">${timeOptions}</select></label><label>Finish by<select data-sem-pref="end">${timeOptions}</select></label><label>Keep free<select data-sem-pref="dayOff"><option value="">No day preference</option>${weekdays.map(day => `<option value="${day}">${({ Mon: 'Monday', Tue: 'Tuesday', Wed: 'Wednesday', Thu: 'Thursday', Fri: 'Friday' })[day]}</option>`).join('')}</select></label><label>Preferred professor<select data-sem-pref="professor"><option value="">No preference</option>${professors.map(name => `<option value="${esc(name)}">${esc(name)}</option>`).join('')}</select></label><label>Minimum credits<input type="number" min="1" max="24" data-sem-pref="minCredits"></label><label>Maximum credits<input type="number" min="1" max="24" data-sem-pref="maxCredits"></label></div><button class="button primary" type="button" data-sem-action="generate">Preview a plan</button></details>`);
    $('planner-chat').insertAdjacentHTML('afterend', '<div class="sem-ai-preview" id="sem-ai-preview" hidden></div>');
    root.querySelectorAll('.chat-starters button').forEach(button => { button.dataset.semAction = 'quick-prompt'; button.removeAttribute('data-action'); });
    root.querySelector('.intelligence-heading strong').textContent = 'Syllo AI';
    const conversation=document.createElement('div');conversation.className='sem-conversation';
    root.querySelector('.intelligence-heading').after(conversation);
    conversation.append($('planner-chat'),root.querySelector('.chat-starters'),$('planner-chat-form'));
    const preferencesPanel=root.querySelector('.sem-ai-preferences');
    preferencesPanel.open=Boolean(preferences().detailsOpen);
    preferencesPanel.querySelector('summary').textContent='Customize your preferences';
    preferencesPanel.querySelector('p').textContent='Prefer using controls? Set times, a day off, professors, and credits here.';
    preferencesPanel.addEventListener('toggle',()=>{preferences().detailsOpen=preferencesPanel.open;persistUI()});
    root.querySelector('.chat-disclaimer').textContent = 'Enter to send · Shift+Enter for a new line. Demo suggestions—review before applying.';
    $('planner-chat-input').setAttribute('placeholder', 'Try “Fridays free, 12 to 16 credits”…');
    $('planner-chat-input').setAttribute('aria-label','Tell Syllo your schedule preferences');
    $('planner-chat-form').querySelector('button').textContent='Send';
    $('planner-chat-input').addEventListener('keydown',event=>{if(event.key==='Enter'&&!event.shiftKey&&!event.isComposing){event.preventDefault();$('planner-chat-form').requestSubmit()}});
    document.body.insertAdjacentHTML('beforeend', '<dialog class="sem-dialog" id="sem-dialog" aria-labelledby="sem-dialog-title"><button class="sem-dialog-x" data-sem-action="close-dialog" aria-label="Close dialog">×</button><h2 id="sem-dialog-title"></h2><div id="sem-dialog-body"></div></dialog>');
    root.addEventListener('click', onClick); $('sem-dialog').addEventListener('click', onClick);
    $('sem-dialog').addEventListener('click', event => { if (event.target === $('sem-dialog')) { const rect = $('sem-dialog').getBoundingClientRect(); if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) closeDialog(); } });
    $('course-search').addEventListener('focus', () => { renderSearch(); setSearch(true); });
    $('course-search').addEventListener('input', () => { renderSearch(); setSearch(true); });
    $('course-search').addEventListener('keydown', event => { if (event.key === 'Enter') { event.preventDefault(); renderSearch(); setSearch(true); $('course-search-results').querySelector('button:not([disabled])')?.focus(); } });
    $('sem-search-mode').addEventListener('change', event => { searchMode = event.target.value; renderSearch(); setSearch(true); });
    root.addEventListener('change', event => {
      if (event.target.dataset.semPref) readPreference(event.target);
    });
    $('planner-chat-input').addEventListener('input', event => { state().draft = event.target.value; persistUI(); });
    $('planner-chat-form').addEventListener('submit', event => { event.preventDefault(); sendPrompt($('planner-chat-input').value); });
    root.addEventListener('contextmenu', event => { const item = event.target.closest('[data-sem-course]'); if (item) openContext(event, item.dataset.semCourse); });
    root.addEventListener('keydown', event => {
      const item = event.target.closest('[data-sem-course]');
      if (item && event.target === item && (event.key === 'Enter' || event.key === ' ')) { event.preventDefault(); S().openCourse(item.dataset.semCourse); }
      if (item && (event.key === 'ContextMenu' || event.shiftKey && event.key === 'F10')) { event.preventDefault(); const rect = item.getBoundingClientRect(); openContext({ preventDefault() {}, clientX: rect.left + 20, clientY: rect.top + 20 }, item.dataset.semCourse); }
    });
    root.addEventListener('dragstart', event => {
      const item = event.target.closest('[data-sem-drag]');
      if (!item || !eligible(item.dataset.semDrag).ok || planned(item.dataset.semDrag)) { event.preventDefault(); return; }
      event.dataTransfer.setData('application/x-syllo-course', item.dataset.semDrag); event.dataTransfer.setData('text/plain', item.dataset.semDrag); event.dataTransfer.effectAllowed = 'copy';
      setTimeout(() => setSearch(false), 0);
    });
    const calendar = $('planner-week-calendar');
    calendar.addEventListener('dragover', event => { if ([...event.dataTransfer.types].includes('application/x-syllo-course')) { event.preventDefault(); event.dataTransfer.dropEffect = 'copy'; calendar.classList.add('dragging'); } });
    calendar.addEventListener('dragleave', event => { if (!calendar.contains(event.relatedTarget)) calendar.classList.remove('dragging'); });
    calendar.addEventListener('drop', event => { event.preventDefault(); calendar.classList.remove('dragging'); const id = event.dataTransfer.getData('application/x-syllo-course'); if (id) add(id); });
    document.addEventListener('pointerdown', event => { if (!event.target.closest('#course-search-area')) setSearch(false); if (!event.target.closest('#calendar-context-menu')) closeContext(); });
    document.addEventListener('keydown', event => {
      if (!root.classList.contains('active')) return;
      const editing = event.target.closest('input, textarea, select, [contenteditable="true"]');
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'z' && !event.shiftKey && !editing && !$('sem-dialog').open) { event.preventDefault(); preview = null; S().undo(); renderPreview(); }
      if (event.key === 'Escape') { setSearch(false); closeContext(); }
      if (contextId && ['ArrowDown', 'ArrowUp'].includes(event.key)) { event.preventDefault(); const buttons = [...$('calendar-context-menu').querySelectorAll('button')]; const at = buttons.indexOf(document.activeElement); buttons[(at + (event.key === 'ArrowDown' ? 1 : -1) + buttons.length) % buttons.length]?.focus(); }
    });
    window.addEventListener('resize', closeContext);
    S().subscribe(render); render();
    setInterval(updateCountdown, 60000);
  }
  window.SylloPlanner = { init, render, add, remove, generatePreview, getPreview: () => preview };
})();
