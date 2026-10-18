/* Connected four-year planning and advisor review. Uses the shared Syllo store. */
(() => {
  'use strict';
  let initialized = false;
  let activeGroup = 'major';
  let pickerSlot = null;
  let pickerQuery = '';
  let pickerReturnFocus = null;
  let reviewDraft = '';
  let reviewCourse = '';
  let courseSearchQuery='',courseSearchOpen=false,courseSearchTerm='spring-2027';
  let searchTags=new Set(),profileOpen=false;
  // Multi-select: course ids picked with Shift+click or a marquee box. Never stored in the plan.
  const selected=new Set();
  let marquee=null;
  const api = () => window.Syllo;
  const L = () => window.SylloPlannerLogic;
  const SEARCH_TAGS=[['code','Course code'],['major','Major'],['minor','Minor'],['gened','GenEd'],['interest','Interest']];
  const programCourseIds = () => (api().state.minors||[]).flatMap(id=>api().programs[id]?.courses||[]);
  const categoryOf = id => L().courseCategory(id, api().catalog[id], programCourseIds());
  /** completed | planned (in a semester or elective slot) | staged (bucket) | none */
  function whereIs(id){const S=api(),at=S.locate(id);if(S.completed.has(id))return 'completed';if(at==='bucket')return 'staged';if(at||Object.values(S.state.electives).includes(id))return 'planned';return 'none'}
  const isSelectable = id => { const at=api().locate(id); return !!api().catalog[id] && !api().completed.has(id) && at!==null && !termById(at)?.past; };
  const safe = value => api().escape(String(value ?? ''));
  const issueText = issue => typeof issue === 'string' ? issue : issue.message || issue.reason || 'Review this requirement.';
  const termById = id => api().terms.find(term => term.id === id);
  const termCourses = term => (api().state.degree[term] || []).filter(id => api().catalog[id]);
  const slotList = () => api().slots || [];
  const termSlots = term => slotList().filter(slot => slot.term === term);
  const courseReason = course => course.requirement || (course.required ? 'Major requirement' : 'Free elective');
  const formatDate = value => new Date(value).toLocaleString('en-US', { month:'short', day:'numeric', hour:'numeric', minute:'2-digit' });
  const reviewLabel = () => ({ draft:'Draft plan', submitted:'Awaiting advisor review', 'changes-requested':'Changes requested', approved:'Approved in demo' }[api().state.review.status] || 'Draft plan');
  const allFutureCourses = () => api().terms.filter(term => !term.past).flatMap(term => termCourses(term.id));
  const slotFilled = slot => api().state.electives[slot.id] && api().catalog[api().state.electives[slot.id]];
  const futureCredits = term => termCourses(term.id).reduce((sum,id) => sum + api().catalog[id].credits,0) + termSlots(term.id).reduce((sum,slot) => sum + (slotFilled(slot)?.credits || 0),0);

  const hiddenTerms = () => Array.isArray(api().state.preferences.hiddenDegreeTerms) ? api().state.preferences.hiddenDegreeTerms : [];
  function setTermVisibility(ids,visible){
    api().change(visible?'Add semester to view':'Remove semester from view',state=>{
      const hidden=new Set(hiddenTerms());ids.forEach(id=>visible?hidden.delete(id):hidden.add(id));
      state.preferences.hiddenDegreeTerms=[...hidden];
    });
  }
  function renderViewControls(){
    const hidden=hiddenTerms(),available=api().terms.filter(term=>hidden.includes(term.id));
    return `<div class="degree-view-controls"><div class="degree-year-buttons" role="group" aria-label="Years to display">${[0,1,2,3].map(year=>{
      const terms=api().terms.slice(year*2,year*2+2),visible=terms.some(term=>!hidden.includes(term.id));
      return `<button type="button" data-degree-action="toggle-year" data-year="${year}" aria-pressed="${visible}" title="${visible?'Remove':'Add'} Year ${year+1} ${visible?'from':'to'} view">${visible?'✓':'＋'} Year ${year+1}</button>`;
    }).join('')}</div><div class="degree-add-semester"><label for="degree-restore-term">Add semester</label><select id="degree-restore-term" ${available.length?'':'disabled'}><option value="">${available.length?'Choose a semester':'All semesters shown'}</option>${available.map(term=>`<option value="${safe(term.id)}">${safe(term.label)}</option>`).join('')}</select><button type="button" data-degree-action="restore-term" ${available.length?'':'disabled'}>＋ Add</button></div></div>`;
  }

  function notify(text) {
    api().state.review.notifications.push({id:`notice-${Date.now()}-${Math.random().toString(36).slice(2,6)}`,text,read:false,createdAt:new Date().toISOString()});
  }

  function auditSummary(audit) {
    const openSlots = slotList().filter(slot => !slotFilled(slot)).length;
    if (audit.valid) return 'All required courses are placed, prerequisites are in order, and the demo plan reaches 120 credits.';
    const items = [];
    if (openSlots) items.push(`${openSlots} elective ${openSlots === 1 ? 'slot needs' : 'slots need'} a course`);
    if (audit.totalCredits < 120) items.push(`${120 - audit.totalCredits} credits still to plan`);
    if (audit.missing?.length) items.push(`${audit.missing.length} required ${audit.missing.length === 1 ? 'course is' : 'courses are'} missing`);
    return items.join(' · ') || 'Resolve the planning issues below before submitting for review.';
  }

  function render() {
    const shell = document.querySelector('[data-screen="requirements"] .degree-shell');
    if (!shell || !api()) return;
    const S = api(), audit = S.degreeAudit();
    const historical = S.historicalCredits || 82;
    [...selected].forEach(id=>{if(!isSelectable(id))selected.delete(id)});
    const focus=document.activeElement?.id;
    shell.innerHTML = `
      <header class="dp-header">
        <div class="dp-title"><span class="dp-university">Lehigh University</span><h1>Degree Planner</h1></div>
        ${renderProfile()}
      </header>
      <section class="degree-course-search dp-search" aria-label="Search courses, minors and concentrations">
        <div class="dp-search-row">
          <label class="dp-search-input"><span class="syllo-sr-only">Search courses, minors, concentrations and interests</span><svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="11" cy="11" r="7"/><path d="m16 16 5 5"/></svg><input id="degree-course-query" type="search" placeholder="Search courses, a minor or concentration, or an interest" value="${safe(courseSearchQuery)}" autocomplete="off" aria-controls="degree-course-results" aria-expanded="${courseSearchOpen}" aria-describedby="dp-search-tags-label"></label>
          <div class="dp-search-tags" role="group" aria-labelledby="dp-search-tags-label"><span id="dp-search-tags-label" class="dp-search-tags-label">Filter</span>${SEARCH_TAGS.map(([id,label])=>`<button type="button" class="dp-tag tag-${id}" data-degree-tag="${id}" aria-pressed="${searchTags.has(id)}">${label}</button>`).join('')}</div>
          <label class="dp-search-term"><span>Add to</span><select id="degree-course-term">${S.terms.filter(term=>!term.past).map(term=>`<option value="${term.id}" ${term.id===courseSearchTerm?'selected':''}>${safe(term.label)}</option>`).join('')}</select></label>
        </div>
        <div id="degree-course-results" class="degree-course-results" ${courseSearchOpen?'':'hidden'}></div>
      </section>
      <section class="degree-four-year-plan" aria-labelledby="dp-calendar-title">
        <div class="dp-calendar-head">
          <h2 id="dp-calendar-title">Degree calendar</h2>
          <p class="dp-hint"><kbd>Shift</kbd>+click or drag a box to select several</p>
          <div class="dp-legend" aria-label="Course color key">${Object.entries(L().CATEGORIES).map(([id,c])=>`<span class="cat-${id}"><i aria-hidden="true"></i>${safe(c.label)}</span>`).join('')}</div>
          <div class="dp-history" role="group" aria-label="Undo and redo">
            <button type="button" class="dp-icon-button" data-degree-action="undo" ${S.canUndo?'':'disabled'} aria-label="${S.canUndo?`Undo: ${safe(S.undoLabel)}`:'Undo, nothing to undo'}" title="Undo (Ctrl or ⌘ + Z)"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M9 14 4 9l5-5"/><path d="M4 9h10.5a5.5 5.5 0 0 1 0 11H11"/></svg></button>
            <button type="button" class="dp-icon-button" data-degree-action="redo" ${S.canRedo?'':'disabled'} aria-label="${S.canRedo?`Redo: ${safe(S.redoLabel)}`:'Redo, nothing to redo'}" title="Redo (Ctrl or ⌘ + Shift + Z)"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m15 14 5-5-5-5"/><path d="M20 9H9.5a5.5 5.5 0 0 0 0 11H13"/></svg></button>
          </div>
          ${renderViewControls()}
        </div>
        <div class="degree-calendar-scroll" tabindex="0" role="region" aria-label="Degree calendar, all semesters"><div class="degree-year-grid degree-semester-calendar" style="--visible-terms:${Math.max(1,api().terms.filter(term=>!hiddenTerms().includes(term.id)).length)}">${hiddenTerms().length===api().terms.length?'<div class="degree-view-empty"><p>No semesters shown. Add a year above or restore a semester.</p><button type="button" data-degree-action="show-all">Show all semesters</button></div>':[0,1,2,3].map(year=>renderYear(year)).join('')}</div></div>
        <div class="dp-selection-bar" id="dp-selection-bar" role="region" aria-label="Selected courses" hidden></div>
      </section>
      ${renderBucket()}
      <section class="degree-review-callout dp-review ${audit.valid?'ready':''}" aria-label="Plan review">
        <div class="dp-review-status"><strong>${audit.valid?'Your plan is ready for review.':'Finish your graduation plan.'}</strong><span class="degree-review-status">${safe(reviewLabel())}</span></div>
        <div class="degree-credit-summary" aria-label="Degree progress"><strong>${historical}/120 credits completed</strong><span>${audit.plannedCredits} planned</span><span>${Math.max(0,120-audit.totalCredits)} to plan</span>${audit.valid?`<span>Graduation · ${safe(audit.graduation)}</span>`:''}</div>
        ${!audit.valid && audit.issues?.length?`<details class="degree-audit-details dp-issues"><summary>${audit.issues.length} ${audit.issues.length===1?'item needs':'items need'} attention</summary><ul>${audit.issues.map(issue=>`<li>${safe(issueText(issue))}</li>`).join('')}</ul></details>`:''}
        <button class="button primary" type="button" data-degree-action="submit-review" ${!audit.valid || S.state.review.status==='submitted' || S.state.review.status==='approved'?'disabled':''}>${S.state.review.status==='submitted'?'Sent for review':S.state.review.status==='approved'?'Plan approved':'Send to advisor'}<span>→</span></button>
        <p class="degree-demo-note">Demo curriculum and rules for exploring Syllo, not an official Lehigh degree audit.</p>
      </section>`;
    renderCourseResults();
    renderSelectionBar();
    if (pickerSlot) renderPickerResults();
    if (focus && focus !== 'degree-course-query') document.getElementById(focus)?.focus({preventScroll:true});
  }

  function renderProfile(){
    const S=api(),advisor=S.state.role==='advisor';
    return `<div class="dp-profile"><button type="button" class="dp-profile-button" id="dp-profile-button" data-degree-action="toggle-profile" aria-haspopup="menu" aria-expanded="${profileOpen}" aria-controls="dp-profile-menu"><span class="dp-avatar" aria-hidden="true">DH</span><span class="dp-profile-name">David Hwang</span><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 9 6 6 6-6"/></svg></button>
      <div class="dp-profile-menu" id="dp-profile-menu" role="menu" aria-labelledby="dp-profile-button" ${profileOpen?'':'hidden'}><p class="dp-profile-meta" role="none">${advisor?'Advisor demo':'Student demo'}</p><button type="button" role="menuitem" data-action="open-schedules">Plan My Semester</button><button type="button" role="menuitem" data-action="open-advisor">Advisor portal</button><button type="button" role="menuitem" data-action="open-saved">Saved schedules</button><button type="button" role="menuitem" data-action="switch-role">Switch to ${advisor?'student':'advisor'} demo</button></div></div>`;
  }

  function trackerMarkup({label,category,completed,planned,staged,total,unassigned}){
    const pct=n=>total?Math.min(100,n/total*100):0;
    const spoken=`${label}: ${completed} of ${total} credits completed, ${planned} planned, ${staged} staged${unassigned!==undefined?`, ${unassigned} unassigned`:''}`;
    return `<article class="dp-tracker cat-${category}"><div class="dp-tracker-head"><i aria-hidden="true"></i><strong>${safe(label)}</strong></div><p><b>${completed} / ${total}</b> credits completed</p><div class="dp-meter" role="img" aria-label="${safe(spoken)}"><span class="done" style="width:${pct(completed)}%"></span><span class="planned" style="width:${pct(planned)}%"></span><span class="staged" style="width:${pct(staged)}%"></span></div><small>${planned} planned · ${staged} staged${unassigned!==undefined?` · <b>${unassigned} unassigned</b>`:''}</small></article>`;
  }
  function renderBucket(){
    const S=api(),staged=S.state.bucket.filter(id=>S.catalog[id]);
    const groups=S.requirementGroups.map(group=>{const p=L().creditProgress(group.courses,S.catalog,whereIs);return trackerMarkup({label:group.label,category:group.category,completed:p.completedCredits,planned:p.plannedCredits,staged:p.stagedCredits,total:group.credits})});
    const minors=(S.state.minors||[]).map(pid=>{const program=S.programs[pid];if(!program)return '';const p=L().creditProgress(program.courses,S.catalog,whereIs);return trackerMarkup({label:program.name,category:'minor',completed:p.completedCredits,planned:p.plannedCredits,staged:p.stagedCredits,total:p.totalCredits,unassigned:p.totalCredits-p.completedCredits-p.plannedCredits})});
    return `<section class="degree-bucket" aria-labelledby="degree-bucket-title">
      <div class="dp-bucket-head"><h2 id="degree-bucket-title">Requirements &amp; unassigned courses</h2><span>${staged.length} staged · drag one into a semester, or drag a planned course here to unassign it</span></div>
      <div class="dp-bucket-body"><div class="dp-trackers" role="list" aria-label="Elective and minor progress">${[...groups,...minors].map(card=>`<div role="listitem">${card}</div>`).join('')}</div>
      <div class="dp-bucket-drop" data-degree-bucket role="list" aria-label="Staged courses, not yet in a semester">${staged.length?staged.map(id=>`<div role="listitem">${renderDegreeCourse(id,'bucket')}</div>`).join(''):'<p class="dp-bucket-empty">Search for a minor, concentration or course and choose <b>Stage</b>, or drag a planned course here. Staged courses wait here until you place them.</p>'}</div></div>
    </section>`;
  }

  function renderSelectionBar(){
    const bar=document.getElementById('dp-selection-bar');if(!bar)return;
    const ids=[...selected];
    document.querySelectorAll('[data-degree-course]').forEach(card=>{const on=selected.has(card.dataset.degreeCourse);card.classList.toggle('is-selected',on);card.querySelector('.degree-course-info')?.setAttribute('aria-description',on?'Selected':'')});
    bar.hidden=!ids.length;
    if(!ids.length){bar.innerHTML='';return}
    const S=api();
    bar.innerHTML=`<strong>${ids.length} ${ids.length===1?'course':'courses'} selected</strong><label>Move to <select id="dp-move-target"><option value="bucket">Staging area</option>${S.terms.filter(t=>!t.past).map(t=>`<option value="${t.id}">${safe(t.label)}</option>`).join('')}</select></label><button type="button" class="button primary" data-degree-action="move-selected">Move</button><button type="button" class="button quiet" data-degree-action="remove-selected">Remove</button><button type="button" class="button quiet" data-degree-action="clear-selection">Clear</button><small>Drag any selected card to move them all · Delete removes · Esc clears</small>`;
  }
  function setSelection(ids){selected.clear();ids.forEach(id=>{if(isSelectable(id))selected.add(id)});renderSelectionBar()}
  function toggleSelected(id){if(!isSelectable(id))return;selected.has(id)?selected.delete(id):selected.add(id);renderSelectionBar()}
  function clearSelection(){if(selected.size){selected.clear();renderSelectionBar()}}
  function reportMove(result,to){
    const S=api();
    if(!result.ok){S.showToast('Courses stay in place',result.reasons.join(' '));return false}
    S.showToast(`${result.moved.length===1?S.catalog[result.moved[0]].code:result.moved.length+' courses'} moved`,to==='bucket'?(result.warnings?.length?result.warnings.join(' '):'In the staging area. Drag them into a semester when you are ready.'):`${termById(to).label} · Prerequisites and term offering checked.`);
    return true;
  }
  function moveSelection(to){const ids=[...selected];if(!ids.length)return;if(reportMove(api().moveCourses(ids,to),to))clearSelection()}
  function removeSelection(){
    const S=api(),removed=S.removeCourses([...selected]);
    if(removed.length){clearSelection();S.showToast(`${removed.length===1?S.catalog[removed[0]].code:removed.length+' courses'} removed`,'Press Ctrl or ⌘ + Z to undo.')}
  }

  function addCourseReasons(id){
    const S=api(),c=S.catalog[id],term=termById(courseSearchTerm),index=S.terms.indexOf(term),reasons=[];
    if(!c||!term||term.past)return ['Choose a future semester.'];
    if(S.completed.has(id))return ['Already completed'];
    const plannedTerm=S.terms.find(t=>termCourses(t.id).includes(id))||S.terms.find(t=>slotList().some(slot=>slot.term===t.id&&S.state.electives[slot.id]===id));
    // Staged (bucket) courses aren't "planned" yet, so they can still be added straight to a semester.
    if(plannedTerm)return [`Already planned · ${plannedTerm.label}`];
    if(!c.terms.includes(term.season))reasons.push(`Offered in ${c.terms.join(' and ')} only.`);
    c.prereqs.forEach(pre=>{if(!S.completed.has(pre)&&!S.terms.slice(0,index).some(t=>termCourses(t.id).includes(pre)))reasons.push(`Take ${S.catalog[pre].code} before ${term.label}.`)});
    const total=S.termCredits(term.id)+c.credits;
    if(total>S.maxTermCredits)reasons.push(`${term.label} would have ${total} credits; a semester can have at most ${S.maxTermCredits}.`);
    return reasons;
  }
  function renderCourseResults(){
    const holder=document.getElementById('degree-course-results');if(!holder)return;
    const S=api(),result=L().degreeSearch(S.catalog,S.programs,courseSearchQuery,[...searchTags],programCourseIds());
    const open=courseSearchOpen&&(courseSearchQuery.trim()||searchTags.size);
    holder.hidden=!open;
    document.getElementById('degree-course-query').setAttribute('aria-expanded',String(!!open));
    if(!open){holder.innerHTML='';return}
    const total=result.programs.length+result.courses.length;
    const programMarkup=result.programs.map(pid=>{
      const p=S.programs[pid],declared=(S.state.minors||[]).includes(pid),toStage=p.courses.filter(id=>whereIs(id)==='none'),credits=p.courses.reduce((n,id)=>n+(S.catalog[id]?.credits||0),0);
      return `<article class="dp-program-result cat-minor"><div><span class="dp-cat-tag">${p.kind==='minor'?'Minor':'Concentration'}</span><strong>${safe(p.name)}</strong><p>${p.courses.length} courses · ${credits} credits · ${p.courses.map(id=>safe(S.catalog[id].code)).join(', ')}</p>${declared?'<small>In your plan</small>':''}</div><div class="degree-search-result-actions"><button type="button" class="button primary" data-degree-action="add-program" data-program="${pid}" ${declared&&!toStage.length?'disabled':''}>${declared&&!toStage.length?'Added':declared?`Stage ${toStage.length} remaining`:`Add ${p.kind} · stage ${toStage.length}`}</button></div></article>`;
    }).join('');
    const courseMarkup=result.courses.map(id=>{
      const c=S.catalog[id],reasons=addCourseReasons(id),cat=categoryOf(id),where=whereIs(id);
      return `<article class="cat-${cat}"><div><span class="dp-cat-tag">${safe(L().CATEGORIES[cat].label)}</span><strong>${safe(c.code)} · ${safe(c.name)}</strong><p>${c.credits} ${c.credits===1?'credit':'credits'} · ${safe(c.requirement)}</p><small>Prerequisites: ${c.prereqs.length?c.prereqs.map(pre=>safe(S.catalog[pre].code)).join(', '):'None in demo'}</small>${reasons.length?`<span class="degree-search-reason">${safe(reasons.join(' '))}</span>`:''}</div><div class="degree-search-result-actions"><button type="button" class="button quiet" data-degree-action="details" data-course="${id}">Details</button><button type="button" class="button quiet" data-degree-action="stage-course" data-course="${id}" ${where==='none'?'':'disabled'}>${where==='staged'?'Staged':'Stage'}</button><button type="button" class="button primary" data-degree-action="add-search-course" data-course="${id}" ${reasons.length?'disabled':''}>Add to ${safe(termById(courseSearchTerm)?.label||'semester')}</button></div></article>`;
    }).join('');
    holder.innerHTML=`<div class="degree-search-result-heading"><span>${total} ${total===1?'result':'results'}${searchTags.size?` · filtered by ${[...searchTags].map(t=>SEARCH_TAGS.find(([id])=>id===t)[1]).join(', ')}`:''}</span><button type="button" data-degree-action="close-course-search" aria-label="Close search results">×</button></div>${programMarkup}${courseMarkup}${total?'':'<p class="degree-search-empty">No matches. Try a course code like “cse 17”, a minor, or another interest.</p>'}`;
  }
  function addSearchCourse(id){
    const S=api(),reasons=addCourseReasons(id);if(reasons.length){S.showToast('Course cannot be added',reasons.join(' '));return;}
    const target=courseSearchTerm,c=S.catalog[id],slot=!c.required?slotList().find(slot=>!S.state.electives[slot.id]):null;
    courseSearchOpen=false;
    S.change(`Add ${c.code} to degree plan`,state=>{
      state.bucket=state.bucket.filter(x=>x!==id);
      if(slot){state.electives[slot.id]=id;state.preferences.degreeElectiveTerms={...state.preferences.degreeElectiveTerms,[slot.id]:target};}
      else state.degree[target].push(id);
      state.preferences.hiddenDegreeTerms=hiddenTerms().filter(term=>term!==target);
    });
    S.showToast(`${c.code} added`,`${termById(target).label}${slot?' · Elective slot filled':''}`);
  }

  function renderYear(index) {
    const terms = api().terms.slice(index*2,index*2+2);
    const visible=terms.filter(term=>!hiddenTerms().includes(term.id));
    if(!visible.length)return '';
    return `<section class="degree-year"><div class="degree-year-heading"><span>Year ${index+1} classes</span><strong>${terms[0]?.year || 2024+index}–${terms[1]?.year || 2025+index}</strong></div>${visible.map(renderTerm).join('')}</section>`;
  }

  function renderTerm(term) {
    const ids = termCourses(term.id);
    const slots = termSlots(term.id);
    return `<section class="degree-term ${term.past?'is-completed':'is-future'} ${term.id===api().nextTerm?'is-next-semester':''}" data-degree-term="${safe(term.id)}" aria-label="${safe(term.label)}${term.past?', completed':''}"><div class="degree-term-heading"><h3>${term.past?'<span aria-hidden="true">▣ </span>':''}${safe(term.label)}</h3><span>${term.past?'Completed':`${futureCredits(term)} credits${term.id===api().nextTerm?' · Next semester':''}`}</span><button class="degree-remove-term" type="button" data-degree-action="hide-term" data-term="${safe(term.id)}" aria-label="Remove ${safe(term.label)} from view" title="Remove semester from view">×</button></div><div class="degree-term-courses">${ids.map(id=>renderDegreeCourse(id,term)).join('')}${slots.map(slot=>renderSlot(slot)).join('')}${!ids.length&&!slots.length?`<p class="degree-term-empty">${term.past?'Recorded in transcript.':'Drag a course here.'}</p>`:''}</div>${term.id===api().nextTerm?'<button class="degree-calendar-link" data-action="degree-to-calendar">Open weekly calendar →</button>':''}${term.past?'<small class="degree-history-note">Read-only transcript summary</small>':''}</section>`;
  }

  function renderDegreeCourse(id,term) {
    const c=api().catalog[id],from=term==='bucket'?'bucket':term.id,locked=term!=='bucket'&&term.past,cat=categoryOf(id),info=L().CATEGORIES[cat];
    return `<article class="degree-course cat-${cat} ${locked?'is-completed':''} ${selected.has(id)?'is-selected':''}" draggable="${!locked}" data-degree-course="${safe(id)}" data-degree-from="${safe(from)}"><button class="degree-course-info" data-degree-action="details" data-course="${safe(id)}" type="button" title="${safe(info.name)} · ${safe(courseReason(c))}${locked?'':' · Shift+click to select'}"><span>${safe(c.code)} <small>${c.credits} cr</small></span><strong>${safe(c.name)}</strong><b class="dp-cat-tag">${safe(info.label)}<span class="syllo-sr-only">: ${safe(info.name)}</span></b><em>${safe(courseReason(c))}</em></button>${from==='bucket'?`<button type="button" class="dp-card-remove" data-degree-action="unstage" data-course="${safe(id)}" aria-label="Remove ${safe(c.code)} from the staging area" title="Remove from staging">×</button>`:''}</article>`;
  }

  function renderSlot(slot) {
    const c=slotFilled(slot);
    if (!c) { const room=Math.max(0,api().maxTermCredits-api().termCredits(slot.term)); return `<button type="button" class="degree-elective-slot" data-degree-action="choose-elective" data-slot="${safe(slot.id)}"><span>＋</span><strong>Choose an elective</strong><small>Any credits · up to ${room} fit</small></button>`; }
    const id=api().state.electives[slot.id];
    const cat=categoryOf(id);
    return `<article class="degree-course elective-filled cat-${cat}" draggable="true" data-degree-course="${safe(id)}" data-degree-from="${safe(slot.term)}" data-degree-slot="${safe(slot.id)}"><button class="degree-course-info" type="button" data-degree-action="details" data-course="${safe(id)}"><span>${safe(c.code)} <small>${c.credits} cr</small></span><strong>${safe(c.name)}</strong><b class="dp-cat-tag">${safe(L().CATEGORIES[cat].label)}</b><em>Free elective</em></button><div class="degree-slot-actions"><button type="button" data-degree-action="choose-elective" data-slot="${safe(slot.id)}">Change</button><button type="button" data-degree-action="clear-elective" data-slot="${safe(slot.id)}" aria-label="Remove ${safe(c.code)} from elective slot">Remove</button></div></article>`;
  }

  function renderGroup() {
    const holder=document.getElementById('degree-group-detail');
    if(!holder)return;
    const S=api(), planned=new Set(allFutureCourses());
    let ids=[];
    let description='';
    if(activeGroup==='major'){ids=Object.keys(S.catalog).filter(id=>S.catalog[id].required&&/major|core|capstone/i.test(courseReason(S.catalog[id])));description='Required major courses are prefilled in the template. Each move checks prerequisites and demo term offerings.';}
    else if(activeGroup==='minor'){ids=Object.keys(S.catalog).filter(id=>/minor/i.test(courseReason(S.catalog[id])));description='These courses contribute to the Data Science minor in this demo plan.';}
    else if(activeGroup==='general'){ids=Object.keys(S.catalog).filter(id=>S.catalog[id].required&&/humanit|general/i.test(courseReason(S.catalog[id])));description='The transcript contains completed general education coursework. The remaining humanities course is shown below.';}
    else {ids=slotList().map(slot=>S.state.electives[slot.id]).filter(Boolean);description='Two free elective slots remain in the template. Choose a course of any credit amount, as long as the semester stays at 21 credits or fewer.';}
    holder.innerHTML=`<p>${safe(description)}</p><div class="degree-requirement-list">${ids.map(id=>{const c=S.catalog[id],completed=S.completed.has(id),isPlanned=planned.has(id)||activeGroup==='electives';return `<button type="button" data-degree-action="details" data-course="${safe(id)}"><span><strong>${safe(c.code)}</strong> ${safe(c.name)}</span><em class="${completed?'done':isPlanned?'planned':'remaining'}">${completed?'Completed':isPlanned?'Planned':'Remaining'}</em></button>`;}).join('')||'<span class="degree-muted">Choose an elective in the four-year plan below.</span>'}</div>`;
  }

  function electiveEligibility(id,slot) {
    return api().electiveReasons(id,slot.id);
  }

  function showPicker(slotId) {
    const slot=slotList().find(item=>item.id===slotId);if(!slot)return;
    pickerSlot=slotId;pickerQuery='';pickerReturnFocus=document.activeElement;
    const modal=document.getElementById('degree-elective-dialog');
    modal.innerHTML=`<div class="degree-dialog-backdrop" data-degree-action="close-picker"></div><section class="degree-dialog-card" role="dialog" aria-modal="true" aria-labelledby="degree-picker-title"><div class="degree-dialog-heading"><div><span class="features-kicker">ELECTIVE EXPLORER</span><h2 id="degree-picker-title">Choose an elective for ${safe(termById(slot.term).label)}</h2><p>Any number of credits · ${safe(termById(slot.term).label)} has ${api().termCredits(slot.term)-(api().catalog[api().state.electives[slot.id]]?.credits||0)} of ${api().maxTermCredits} credits planned · Demo catalog</p></div><button type="button" class="degree-dialog-close" data-degree-action="close-picker" aria-label="Close elective explorer">×</button></div><label class="degree-picker-search"><span>Search by name, course code, or interest</span><input id="degree-elective-search" type="search" autocomplete="off" placeholder="Try art, economics, or technology"></label><div id="degree-picker-results" class="degree-picker-results" aria-live="polite"></div></section>`;
    modal.classList.remove('hidden');renderPickerResults();document.getElementById('degree-elective-search').focus();
  }

  function renderPickerResults() {
    const holder=document.getElementById('degree-picker-results'),slot=slotList().find(item=>item.id===pickerSlot);if(!holder||!slot)return;
    const S=api(),query=pickerQuery.trim().toLowerCase();
    const matches=Object.entries(S.catalog).filter(([,c])=>!c.required&&[c.code,c.name,c.interest,c.description].join(' ').toLowerCase().includes(query));
    holder.innerHTML=matches.map(([id,c])=>{const reasons=electiveEligibility(id,slot);return `<article><div><span>${safe(c.code)} · ${c.credits} ${c.credits===1?'credit':'credits'}</span><h3>${safe(c.name)}</h3><p>${safe(c.description||'No description available.')}</p><small>${safe(c.interest||'General interest')} · ${safe((c.terms||[]).join(' / ')||'Term not provided')}</small><small>Prerequisites: ${c.prereqs?.length?c.prereqs.map(p=>safe(S.catalog[p]?.code||p)).join(', '):'None in this demo'}</small>${reasons.length?`<p class="degree-picker-warning">${safe(reasons.join(' '))}</p>`:''}</div><div class="degree-picker-actions"><button type="button" class="button quiet" data-degree-action="details" data-course="${safe(id)}">Details</button><button type="button" class="button primary" data-degree-action="select-elective" data-course="${safe(id)}" ${reasons.length?'disabled':''}>Choose course</button></div></article>`;}).join('')||'<div class="empty-state">No matching demo electives. Try a different search.</div>';
  }

  function closePicker() {
    pickerSlot=null;document.getElementById('degree-elective-dialog')?.classList.add('hidden');pickerReturnFocus?.isConnected&&pickerReturnFocus.focus();
  }

  function moveCourse(id,from,to) {
    if(from===to)return;
    const result=api().moveDegree(id,from,to);
    if(!result.ok){api().showToast('Course stays in place',(result.reasons||[]).map(issueText).join(' '));render();}
    else api().showToast(`${api().catalog[id].code} moved`,`${termById(to).label} · Prerequisites and term offering checked.`);
  }

  function renderAdvisor() {
    const shell=document.querySelector('[data-screen="advisor"] .advisor-shell');if(!shell||!api())return;
    const S=api(),audit=S.degreeAudit(),isAdvisor=S.state.role==='advisor';
    if(!isAdvisor&&!audit.valid){
      shell.innerHTML=`<div class="degree-page-heading"><div><span class="features-kicker">ACADEMIC ADVISOR PORTAL</span><h1>Finish your plan first.</h1><p>Your advisor can review a complete path to graduation.</p></div></div><section class="advisor-gate"><span class="advisor-gate-symbol">↗</span><h2>A few pieces are still missing.</h2><p>${safe(auditSummary(audit))}</p><ul>${(audit.issues||[]).map(issue=>`<li>${safe(issueText(issue))}</li>`).join('')}</ul><button type="button" class="button primary" data-degree-action="back-degree">Continue your degree plan <span>→</span></button><small>Advisor demo accounts can access the review at any time.</small></section>`;
      return;
    }
    const unread=S.state.review.notifications.filter(n=>!n.read).length;
    shell.innerHTML=`<div class="degree-page-heading"><div><span class="features-kicker">ACADEMIC ADVISOR PORTAL</span><h1>David Hwang’s degree plan</h1><p>${isAdvisor?'Review the full plan and leave feedback for David.':'Your four-year plan and your advisor’s feedback, together.'}</p></div><span class="degree-demo-label">${isAdvisor?'Advisor demo':'Student demo'}</span></div>
      <section class="advisor-plan-summary"><div><strong>${safe(reviewLabel())}</strong><p>${audit.totalCredits} / 120 credits · ${audit.valid?`Planned graduation ${safe(audit.graduation||'May 2028')}`:'Graduation requirements incomplete'}</p></div>${!isAdvisor&&unread?`<button type="button" class="button quiet" data-degree-action="mark-feedback-read">${unread} new ${unread===1?'update':'updates'} · Mark read</button>`:''}${!isAdvisor&&audit.valid&&['draft','changes-requested'].includes(S.state.review.status)?'<button type="button" class="button primary" data-degree-action="submit-review">Send updated plan</button>':''}</section>
      <div class="advisor-connected-layout"><section class="advisor-full-plan"><div class="degree-section-heading"><h2>The complete four-year plan</h2><p>Each course shows the requirement it fulfills.</p></div><div class="advisor-term-grid">${S.terms.map(term=>advisorTerm(term)).join('')}</div></section><aside class="advisor-feedback"><h2>Advisor feedback</h2><div class="advisor-feedback-list" id="advisor-feedback-list">${renderComments()}</div>${isAdvisor?`<form id="advisor-feedback-form"><label>Comment on<select id="advisor-feedback-course"><option value="">The entire four-year plan</option>${allFutureCourses().concat(Object.values(S.state.electives).filter(Boolean)).map(id=>`<option value="${safe(id)}" ${reviewCourse===id?'selected':''}>${safe(S.catalog[id].code)} · ${safe(S.catalog[id].name)}</option>`).join('')}</select></label><label>Suggestion or comment<textarea id="advisor-feedback-input" rows="4" placeholder="Explain the change you recommend…" required>${safe(reviewDraft)}</textarea></label><button type="submit" class="button primary">Post feedback</button></form><div class="advisor-decision-actions"><button type="button" class="button quiet" data-degree-action="request-changes">Request changes</button><button type="button" class="button primary" data-degree-action="approve" ${!audit.valid||!['submitted','changes-requested'].includes(S.state.review.status)?'disabled':''}>Approve plan</button></div>${!audit.valid?'<p class="advisor-action-note">Approval becomes available once the graduation checks pass.</p>':S.state.review.status==='draft'?'<p class="advisor-action-note">The student must submit this version before approval.</p>':''}`:'<p class="advisor-action-note">Feedback and decisions appear here when your advisor reviews your plan. Use the degree planner to make changes.</p>'}</aside></div>
      <p class="degree-demo-note">Demo collaboration is saved in this browser. Use the advisor demo role to try commenting and approval; no message is sent to a real advisor.</p>`;
  }

  function advisorTerm(term) {
    const S=api(),ids=termCourses(term.id),slots=termSlots(term.id);
    return `<article class="advisor-term"><div><h3>${safe(term.label)}</h3><span>${term.past?'Completed':`${futureCredits(term)} cr`}</span></div>${ids.map(id=>`<button type="button" data-degree-action="details" data-course="${safe(id)}"><strong>${safe(S.catalog[id].code)} <small>${S.catalog[id].credits} cr</small></strong><span>${safe(S.catalog[id].name)}</span><em>${safe(courseReason(S.catalog[id]))}</em></button>`).join('')}${slots.map(slot=>{const c=slotFilled(slot);return c?`<button type="button" data-degree-action="details" data-course="${safe(S.state.electives[slot.id])}"><strong>${safe(c.code)} <small>${c.credits} cr</small></strong><span>${safe(c.name)}</span><em>Free elective</em></button>`:`<p class="advisor-empty-slot">Free elective · Unfilled</p>`;}).join('')}${!ids.length&&!slots.length?`<p>${term.past?'Recorded in the 82-credit transcript total.':'No courses planned.'}</p>`:''}</article>`;
  }

  function renderComments() {
    const S=api(),comments=S.state.review.comments;
    if(!comments.length)return '<p class="advisor-no-feedback">No feedback yet. Comments about the plan and individual courses will appear here.</p>';
    return comments.map(comment=>`<article class="advisor-feedback-item ${comment.type==='decision'?'decision':''}"><div><strong>Demo advisor</strong><time datetime="${safe(comment.createdAt)}">${safe(formatDate(comment.createdAt))}</time></div>${comment.courseId?`<button type="button" data-degree-action="details" data-course="${safe(comment.courseId)}">${safe(S.catalog[comment.courseId]?.code||comment.courseId)}</button>`:'<span>Entire four-year plan</span>'}<p>${safe(comment.text)}</p></article>`).join('');
  }

  function submitReview() {
    const S=api(),audit=S.degreeAudit();
    if(!audit.valid){S.showToast('Complete your plan first',auditSummary(audit));return;}
    S.change('Submit plan for advisor review',state=>{state.review.status='submitted';state.review.submittedAt=new Date().toISOString();});
    S.showToast('Plan sent for demo review','Switch to the advisor demo role to review this four-year plan.');
    S.navigate('advisor');
  }

  function postFeedback(event) {
    event.preventDefault();const S=api();if(S.state.role!=='advisor')return;
    const input=document.getElementById('advisor-feedback-input'),text=input.value.trim();if(!text){input.focus();return;}
    const courseId=document.getElementById('advisor-feedback-course').value||null;
    S.change('Advisor feedback',state=>{state.review.comments.push({id:`comment-${Date.now()}`,text,courseId,createdAt:new Date().toISOString(),type:'comment'});notify(`Your advisor commented on ${courseId?S.catalog[courseId].code:'your four-year plan'}.`);});
    reviewDraft='';reviewCourse='';renderAdvisor();S.showToast('Feedback posted','David will see a new feedback notification in the student demo.');
  }

  function decide(status) {
    const S=api();if(S.state.role!=='advisor')return;
    if(status==='approved'&&(!S.degreeAudit().valid||!['submitted','changes-requested'].includes(S.state.review.status))){S.showToast('Plan cannot be approved yet','The student must submit a complete graduation plan.');return;}
    const text=status==='approved'?'Your four-year plan is approved in this demo.':'Changes requested. Review the advisor’s comments and update your four-year plan.';
    S.change('Advisor review decision',state=>{state.review.status=status;state.review.comments.push({id:`decision-${Date.now()}`,text,courseId:null,createdAt:new Date().toISOString(),type:'decision'});notify(status==='approved'?'Your advisor approved your degree plan.':'Your advisor requested changes to your degree plan.');});
    S.showToast(status==='approved'?'Demo plan approved':'Changes requested','The student demo has a new notification.');
  }

  function init() {
    if(initialized||!api())return;initialized=true;
    const modal=document.createElement('div');modal.id='degree-elective-dialog';modal.className='degree-elective-dialog hidden';document.body.append(modal);
    document.addEventListener('click',event=>{
      const tag=event.target.closest('[data-degree-tag]');
      if(tag){const id=tag.dataset.degreeTag;searchTags.has(id)?searchTags.delete(id):searchTags.add(id);tag.setAttribute('aria-pressed',String(searchTags.has(id)));courseSearchOpen=true;renderCourseResults();return}
      if(profileOpen&&!event.target.closest('.dp-profile')){profileOpen=false;render();}
      else if(profileOpen&&event.target.closest('#dp-profile-menu [data-action]')){profileOpen=false;}
      const button=event.target.closest('[data-degree-action]');if(!button||button.disabled)return;
      const action=button.dataset.degreeAction,S=api();
      if(action==='details'){
        const id=button.dataset.course,inCard=button.closest('[data-degree-course]');
        if(event.shiftKey&&inCard){event.preventDefault();toggleSelected(id);return}
        clearSelection();S.openCourse(id);
      }
      else if(action==='toggle-profile'){profileOpen=!profileOpen;render();if(profileOpen)document.querySelector('#dp-profile-menu [role=menuitem]')?.focus();else document.getElementById('dp-profile-button')?.focus();}
      else if(action==='undo')S.undo();
      else if(action==='redo')S.redo();
      else if(action==='stage-course'){const c=S.catalog[button.dataset.course];if(S.stageCourses([button.dataset.course]).length)S.showToast(`${c.code} staged`,'Drag it from the staging area into a semester.');}
      else if(action==='add-program'){const p=S.programs[button.dataset.program],r=S.addProgram(button.dataset.program);courseSearchOpen=false;renderCourseResults();S.showToast(`${p.name} ${r.added?'added':'updated'}`,r.staged.length?`${r.staged.length} ${r.staged.length===1?'course':'courses'} staged: ${r.staged.map(id=>S.catalog[id].code).join(', ')}.`:'Every course is already in your plan.');}
      else if(action==='unstage'){S.change(`Remove ${S.catalog[button.dataset.course].code} from staging`,state=>{state.bucket=state.bucket.filter(id=>id!==button.dataset.course)});}
      else if(action==='move-selected')moveSelection(document.getElementById('dp-move-target').value);
      else if(action==='remove-selected')removeSelection();
      else if(action==='clear-selection')clearSelection();
      else if(action==='add-search-course')addSearchCourse(button.dataset.course);
      else if(action==='close-course-search'){courseSearchOpen=false;renderCourseResults();}
      else if(action==='toggle-year'){
        const ids=S.terms.slice(Number(button.dataset.year)*2,Number(button.dataset.year)*2+2).map(term=>term.id);
        setTermVisibility(ids,ids.every(id=>hiddenTerms().includes(id)));
        document.querySelector(`[data-degree-action="toggle-year"][data-year="${button.dataset.year}"]`)?.focus({preventScroll:true});
      }
      else if(action==='hide-term')setTermVisibility([button.dataset.term],false);
      else if(action==='restore-term'){
        const id=document.getElementById('degree-restore-term').value;
        if(id)setTermVisibility([id],true);else document.getElementById('degree-restore-term').focus();
      }
      else if(action==='show-all')setTermVisibility(S.terms.map(term=>term.id),true);
      else if(action==='group'){activeGroup=button.dataset.group;document.querySelectorAll('.degree-group-tabs button').forEach(tab=>tab.setAttribute('aria-selected',String(tab.dataset.group===activeGroup)));renderGroup();}
      else if(action==='choose-elective')showPicker(button.dataset.slot);
      else if(action==='close-picker')closePicker();
      else if(action==='clear-elective')S.change('Remove elective',state=>{state.electives[button.dataset.slot]=null;});
      else if(action==='select-elective'){
        const slot=slotList().find(item=>item.id===pickerSlot),id=button.dataset.course;if(!slot)return;
        const reasons=electiveEligibility(id,slot);if(reasons.length){S.showToast('Choose another elective',reasons.join(' '));return;}
        const slotId=slot.id;closePicker();S.change('Choose elective',state=>{state.electives[slotId]=id;});S.showToast('Elective added',`${S.catalog[id].code} is in ${termById(slot.term).label}.`);
      }
      else if(action==='submit-review')submitReview();
      else if(action==='back-degree')S.navigate('requirements');
      else if(action==='approve')decide('approved');
      else if(action==='request-changes')decide('changes-requested');
      else if(action==='mark-feedback-read')S.change('Read advisor feedback',state=>{state.review.notifications.forEach(n=>{n.read=true;});});
    });
    document.addEventListener('change',event=>{
      if(event.target.id==='degree-course-term'){courseSearchTerm=event.target.value;renderCourseResults();}
      if(event.target.id==='advisor-feedback-course')reviewCourse=event.target.value;
    });
    document.addEventListener('input',event=>{
      if(event.target.id==='degree-course-query'){courseSearchQuery=event.target.value;courseSearchOpen=true;renderCourseResults();}
      if(event.target.id==='degree-elective-search'){pickerQuery=event.target.value;renderPickerResults();}
      if(event.target.id==='advisor-feedback-input')reviewDraft=event.target.value;
    });
    document.addEventListener('submit',event=>{if(event.target.id==='advisor-feedback-form')postFeedback(event);});
    document.addEventListener('focusin',event=>{if(event.target.id==='degree-course-query'){courseSearchOpen=true;renderCourseResults();}});
    document.addEventListener('pointerdown',event=>{if(courseSearchOpen&&!event.target.closest('.degree-course-search')){courseSearchOpen=false;renderCourseResults();}});
    // Marquee selection: drag a box over empty calendar or staging space.
    document.addEventListener('pointerdown',event=>{
      if(event.button!==0||!document.querySelector('[data-screen="requirements"].active'))return;
      const zone=event.target.closest('.degree-calendar-scroll,[data-degree-bucket]');
      if(!zone||event.target.closest('.degree-course,button,select,input,label,a,.degree-elective-slot'))return;
      marquee={x:event.clientX,y:event.clientY,base:new Set(event.shiftKey?selected:[]),active:false};
    });
    document.addEventListener('pointermove',event=>{
      if(!marquee)return;
      if(!marquee.active&&Math.hypot(event.clientX-marquee.x,event.clientY-marquee.y)<6)return;
      if(!marquee.active){marquee.active=true;document.body.classList.add('dp-marquee-active');let box=document.getElementById('dp-marquee');if(!box){box=document.createElement('div');box.id='dp-marquee';box.className='dp-marquee';box.setAttribute('aria-hidden','true');document.body.append(box)}}
      event.preventDefault();
      const rect=L().rectFromPoints(marquee.x,marquee.y,event.clientX,event.clientY),box=document.getElementById('dp-marquee');
      Object.assign(box.style,{left:`${rect.left}px`,top:`${rect.top}px`,width:`${rect.right-rect.left}px`,height:`${rect.bottom-rect.top}px`,display:'block'});
      const hits=[...document.querySelectorAll('[data-screen="requirements"] [data-degree-course]:not([data-degree-slot])')].filter(card=>L().rectsIntersect(rect,card.getBoundingClientRect())).map(card=>card.dataset.degreeCourse);
      setSelection([...marquee.base,...hits]);
    });
    document.addEventListener('pointerup',()=>{
      if(!marquee)return;
      const dragged=marquee.active;marquee=null;document.body.classList.remove('dp-marquee-active');
      const box=document.getElementById('dp-marquee');if(box)box.style.display='none';
      if(!dragged)clearSelection();
      else if(selected.size)api().showToast(`${selected.size} ${selected.size===1?'course':'courses'} selected`,'Drag one to move them all, or use the selection bar.');
    });
    document.addEventListener('dragstart',event=>{
      const card=event.target.closest('[data-degree-course][draggable="true"]');if(!card)return;
      if(event.target.closest('select')){event.preventDefault();return;}
      const fromBucket=card.dataset.degreeFrom==='bucket',source=termById(card.dataset.degreeFrom);
      if((!fromBucket&&(!source||source.past))||api().completed.has(card.dataset.degreeCourse)){event.preventDefault();return;}
      document.body.classList.add('degree-is-dragging');
      const id=card.dataset.degreeCourse,group=selected.has(id)&&selected.size>1?[...selected]:[id];
      event.dataTransfer.setData('application/syllo-degree',JSON.stringify({id,from:card.dataset.degreeFrom,ids:group,slot:card.dataset.degreeSlot||null}));event.dataTransfer.effectAllowed='move';
      document.querySelectorAll('[data-degree-course]').forEach(el=>{if(group.includes(el.dataset.degreeCourse))el.classList.add('is-dragging')});
    });
    document.addEventListener('dragend',()=>{document.body.classList.remove('degree-is-dragging');document.querySelectorAll('.degree-term.is-drop-target,.degree-course.is-dragging,[data-degree-bucket].is-drop-target').forEach(el=>el.classList.remove('is-drop-target','is-dragging'))});
    const dropZone=target=>target.closest('.degree-term.is-future,[data-degree-bucket]');
    document.addEventListener('dragover',event=>{const zone=dropZone(event.target);if(!zone||!Array.from(event.dataTransfer.types).includes('application/syllo-degree'))return;event.preventDefault();event.dataTransfer.dropEffect='move';zone.classList.add('is-drop-target');});
    document.addEventListener('dragleave',event=>{const zone=dropZone(event.target);if(zone&&!zone.contains(event.relatedTarget))zone.classList.remove('is-drop-target');});
    document.addEventListener('drop',event=>{
      const zone=event.target.closest('.degree-term,[data-degree-bucket]');if(!zone)return;const raw=event.dataTransfer.getData('application/syllo-degree');if(!raw)return;
      event.preventDefault();document.body.classList.remove('degree-is-dragging');zone.classList.remove('is-drop-target');
      if(zone.classList.contains('is-completed')){api().showToast('Completed semester is locked','Move this course into a future semester instead.');return}
      try{
        const data=JSON.parse(raw),to=zone.matches('[data-degree-bucket]')?'bucket':zone.dataset.degreeTerm,ids=Array.isArray(data.ids)?data.ids:[data.id];
        if(data.slot){if(to==='bucket'){api().showToast('Elective slots stay in their semester','Use Remove on the elective, or Change to pick another course.');return}moveCourse(data.id,data.from,to);return}
        if(ids.length===1&&data.from!=='bucket'&&to!=='bucket'){moveCourse(data.id,data.from,to);return}
        if(reportMove(api().moveCourses(ids,to),to))clearSelection();
      }catch{/* Ignore unrelated drag data. */}
    });
    document.addEventListener('keydown',event=>{
      const onDegree=!!document.querySelector('[data-screen="requirements"].active'),editing=event.target.closest?.('input,textarea,select,[contenteditable="true"]');
      if(onDegree&&profileOpen&&event.key==='Escape'){profileOpen=false;render();document.getElementById('dp-profile-button')?.focus();return}
      if(onDegree&&profileOpen&&['ArrowDown','ArrowUp'].includes(event.key)){event.preventDefault();const items=[...document.querySelectorAll('#dp-profile-menu [role=menuitem]')],at=items.indexOf(document.activeElement);items[(at+(event.key==='ArrowDown'?1:-1)+items.length)%items.length]?.focus();return}
      if(onDegree&&selected.size&&event.key==='Escape'&&!pickerSlot){clearSelection();}
      if(onDegree&&selected.size&&!editing&&(event.key==='Delete'||event.key==='Backspace')){event.preventDefault();removeSelection();return}
      if(event.key==='Escape'&&courseSearchOpen){courseSearchOpen=false;renderCourseResults();}
      if(!pickerSlot)return;
      if(event.key==='Escape'){event.preventDefault();closePicker();}
      if(event.key==='Tab'){
        const focusable=[...document.querySelectorAll('#degree-elective-dialog button:not(:disabled),#degree-elective-dialog input')];
        const first=focusable[0],last=focusable[focusable.length-1];if(event.shiftKey&&document.activeElement===first){event.preventDefault();last.focus();}else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first.focus();}
      }
    });
    Syllo.subscribe(()=>{render();renderAdvisor();});
    render();renderAdvisor();
  }
  window.SylloDegree={init,render,renderAdvisor};
})();
