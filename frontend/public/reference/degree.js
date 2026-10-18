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
  const api = () => window.Syllo;
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
    shell.innerHTML = `
      <div class="degree-page-heading"><div><span class="features-kicker">DEGREE PLANNER</span><h1>Make room for your future.</h1><p>Computer Science, B.S. · Data Science minor · 2024–2028</p></div><span class="degree-demo-label">Demo curriculum</span></div>
      <div class="degree-credit-summary" aria-label="Degree progress"><strong>${historical}/120 credits completed</strong><span>${audit.plannedCredits} planned</span><span>${Math.max(0,120-audit.totalCredits)} to plan</span>${audit.valid?`<span>Graduation · ${safe(audit.graduation)}</span>`:''}</div>
      <section class="degree-course-search" aria-label="Add a course to your degree plan"><div class="degree-search-heading"><h2>Add a course</h2><span>Demo catalog</span></div><div class="degree-search-controls"><label class="degree-search-query"><span>Search courses</span><input id="degree-course-query" type="search" placeholder="Course name, code, department, or keyword" value="${safe(courseSearchQuery)}" autocomplete="off" aria-controls="degree-course-results" aria-expanded="${courseSearchOpen}"></label><label class="degree-search-term"><span>Add to semester</span><select id="degree-course-term">${S.terms.filter(term=>!term.past).map(term=>`<option value="${term.id}" ${term.id===courseSearchTerm?'selected':''}>${safe(term.label)}</option>`).join('')}</select></label></div><div id="degree-course-results" class="degree-course-results" ${courseSearchOpen?'':'hidden'}></div></section>
      ${window.SylloDegreeAI.markup()}
      <section class="degree-four-year-plan"><div class="degree-section-heading"><div><h2>Your degree calendar</h2><p>Click a course for details and related classes. Drag to a future semester.</p></div></div>${renderViewControls()}<div class="degree-calendar-scroll" tabindex="0" role="region" aria-label="Degree calendar, all semesters"><div class="degree-year-grid degree-semester-calendar" style="--visible-terms:${Math.max(1,api().terms.filter(term=>!hiddenTerms().includes(term.id)).length)}">${hiddenTerms().length===api().terms.length?'<div class="degree-view-empty"><p>No semesters shown. Add a year above or restore a semester.</p><button type="button" data-degree-action="show-all">Show all semesters</button></div>':[0,1,2,3].map(year=>renderYear(year)).join('')}</div></div></section>
      <section class="degree-review-callout ${audit.valid?'ready':''}"><div><strong>${audit.valid?'Your plan is ready for review.':'Finish your graduation plan.'}</strong><p>${safe(auditSummary(audit))}</p><span class="degree-review-status">${safe(reviewLabel())}</span></div><button class="button primary" type="button" data-degree-action="submit-review" ${!audit.valid || S.state.review.status==='submitted' || S.state.review.status==='approved'?'disabled':''}>${S.state.review.status==='submitted'?'Sent for review':S.state.review.status==='approved'?'Plan approved':'Send to advisor'}<span>→</span></button></section>
      ${!audit.valid && audit.issues?.length?`<details class="degree-audit-details"><summary>What still needs attention</summary><ul>${audit.issues.map(issue=>`<li>${safe(issueText(issue))}</li>`).join('')}</ul></details>`:''}
      <p class="degree-demo-note">This is a simulated curriculum and transcript for exploring Syllo. Course offerings, prerequisites, and graduation checks are demo rules, not an official Lehigh degree audit.</p>`;
    renderCourseResults();
    if (pickerSlot) renderPickerResults();
  }

  function addCourseReasons(id){
    const S=api(),c=S.catalog[id],term=termById(courseSearchTerm),index=S.terms.indexOf(term),reasons=[];
    if(!c||!term||term.past)return ['Choose a future semester.'];
    if(S.completed.has(id))return ['Already completed'];
    const plannedTerm=S.terms.find(t=>termCourses(t.id).includes(id))||S.terms.find(t=>slotList().some(slot=>slot.term===t.id&&S.state.electives[slot.id]===id));
    if(plannedTerm)return [`Already planned · ${plannedTerm.label}`];
    if(!c.terms.includes(term.season))reasons.push(`Offered in ${c.terms.join(' and ')} only.`);
    c.prereqs.forEach(pre=>{if(!S.completed.has(pre)&&!S.terms.slice(0,index).some(t=>termCourses(t.id).includes(pre)))reasons.push(`Take ${S.catalog[pre].code} before ${term.label}.`)});
    return reasons;
  }
  function renderCourseResults(){
    const holder=document.getElementById('degree-course-results');if(!holder)return;
    holder.hidden=!courseSearchOpen;
    document.getElementById('degree-course-query').setAttribute('aria-expanded',String(courseSearchOpen));
    const tokens=courseSearchQuery.trim().toLowerCase().split(/\s+/).filter(Boolean);
    const matches=Object.entries(api().catalog).filter(([,c])=>tokens.every(token=>[c.code,c.name,c.description,c.requirement,c.interest].join(' ').toLowerCase().includes(token)));
    holder.innerHTML=`<div class="degree-search-result-heading"><span>${matches.length} ${matches.length===1?'course':'courses'}</span><button type="button" data-degree-action="close-course-search" aria-label="Close course results">×</button></div>${matches.length?matches.map(([id,c])=>{
      const reasons=addCourseReasons(id);
      return `<article><div><strong>${safe(c.code)} · ${safe(c.name)}</strong><p>${c.credits} credits · ${safe(c.requirement)}</p><small>Prerequisites: ${c.prereqs.length?c.prereqs.map(pre=>safe(api().catalog[pre].code)).join(', '):'None in demo'}</small>${reasons.length?`<span class="degree-search-reason">${safe(reasons.join(' '))}</span>`:''}</div><div class="degree-search-result-actions"><button type="button" class="button quiet" data-degree-action="details" data-course="${id}">Details</button><button type="button" class="button primary" data-degree-action="add-search-course" data-course="${id}" ${reasons.length?'disabled':''}>Add course</button></div></article>`;
    }).join(''):'<p class="degree-search-empty">No matching demo courses. Try another name or code.</p>'}`;
  }
  function addSearchCourse(id){
    const S=api(),reasons=addCourseReasons(id);if(reasons.length){S.showToast('Course cannot be added',reasons.join(' '));return;}
    const target=courseSearchTerm,c=S.catalog[id],slot=!c.required?slotList().find(slot=>!S.state.electives[slot.id]&&slot.credits===c.credits):null;
    courseSearchOpen=false;
    S.change(`Add ${c.code} to degree plan`,state=>{
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
    const c=api().catalog[id];
    return `<article class="degree-course ${term.past?'is-completed':''}" draggable="${!term.past}" data-degree-course="${safe(id)}" data-degree-from="${safe(term.id)}"><button class="degree-course-info" data-degree-action="details" data-course="${safe(id)}" type="button"><span>${safe(c.code)} <small>${c.credits} cr</small></span><strong>${safe(c.name)}</strong><em>${safe(courseReason(c))}</em></button></article>`;
  }

  function renderSlot(slot) {
    const c=slotFilled(slot);
    if (!c) return `<button type="button" class="degree-elective-slot" data-degree-action="choose-elective" data-slot="${safe(slot.id)}"><span>＋</span><strong>Choose an elective</strong><small>${slot.credits} credits · Free elective</small></button>`;
    const id=api().state.electives[slot.id];
    return `<article class="degree-course elective-filled" draggable="true" data-degree-course="${safe(id)}" data-degree-from="${safe(slot.term)}"><button class="degree-course-info" type="button" data-degree-action="details" data-course="${safe(id)}"><span>${safe(c.code)} <small>${c.credits} cr</small></span><strong>${safe(c.name)}</strong><em>Free elective</em></button><div class="degree-slot-actions"><button type="button" data-degree-action="choose-elective" data-slot="${safe(slot.id)}">Change</button><button type="button" data-degree-action="clear-elective" data-slot="${safe(slot.id)}" aria-label="Remove ${safe(c.code)} from elective slot">Remove</button></div></article>`;
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
    else {ids=slotList().map(slot=>S.state.electives[slot.id]).filter(Boolean);description='Two free elective slots remain in the template: one 4-credit course and one 3-credit course. Click a blank slot in Spring 2028 to explore courses.';}
    holder.innerHTML=`<p>${safe(description)}</p><div class="degree-requirement-list">${ids.map(id=>{const c=S.catalog[id],completed=S.completed.has(id),isPlanned=planned.has(id)||activeGroup==='electives';return `<button type="button" data-degree-action="details" data-course="${safe(id)}"><span><strong>${safe(c.code)}</strong> ${safe(c.name)}</span><em class="${completed?'done':isPlanned?'planned':'remaining'}">${completed?'Completed':isPlanned?'Planned':'Remaining'}</em></button>`;}).join('')||'<span class="degree-muted">Choose an elective in the four-year plan below.</span>'}</div>`;
  }

  function electiveEligibility(id,slot) {
    const S=api(),c=S.catalog[id],targetIndex=S.terms.findIndex(t=>t.id===slot.term),term=termById(slot.term);
    const reasons=[];
    if(c.required)reasons.push('Reserved for a required course slot.');
    if(c.credits!==slot.credits)reasons.push(`This slot needs ${slot.credits} credits.`);
    if(c.terms?.length&&!c.terms.includes(term.season))reasons.push(`Not offered in ${term.label} in the demo catalog.`);
    for(const prereq of c.prereqs||[]){
      const earlier=S.completed.has(prereq)||S.terms.some((t,index)=>index<targetIndex&&(S.state.degree[t.id]||[]).includes(prereq));
      if(!earlier)reasons.push(`Take ${S.catalog[prereq]?.code||prereq} before ${term.label}.`);
    }
    if(S.terms.some(t=>(S.state.degree[t.id]||[]).includes(id))||slotList().some(other=>other.id!==slot.id&&S.state.electives[other.id]===id))reasons.push('Already included in your degree plan.');
    return reasons;
  }

  function showPicker(slotId) {
    const slot=slotList().find(item=>item.id===slotId);if(!slot)return;
    pickerSlot=slotId;pickerQuery='';pickerReturnFocus=document.activeElement;
    const modal=document.getElementById('degree-elective-dialog');
    modal.innerHTML=`<div class="degree-dialog-backdrop" data-degree-action="close-picker"></div><section class="degree-dialog-card" role="dialog" aria-modal="true" aria-labelledby="degree-picker-title"><div class="degree-dialog-heading"><div><span class="features-kicker">ELECTIVE EXPLORER</span><h2 id="degree-picker-title">Find your ${slot.credits}-credit elective</h2><p>${safe(termById(slot.term).label)} · Demo catalog</p></div><button type="button" class="degree-dialog-close" data-degree-action="close-picker" aria-label="Close elective explorer">×</button></div><label class="degree-picker-search"><span>Search by name, course code, or interest</span><input id="degree-elective-search" type="search" autocomplete="off" placeholder="Try art, economics, or technology"></label><div id="degree-picker-results" class="degree-picker-results" aria-live="polite"></div></section>`;
    modal.classList.remove('hidden');renderPickerResults();document.getElementById('degree-elective-search').focus();
  }

  function renderPickerResults() {
    const holder=document.getElementById('degree-picker-results'),slot=slotList().find(item=>item.id===pickerSlot);if(!holder||!slot)return;
    const S=api(),query=pickerQuery.trim().toLowerCase();
    const matches=Object.entries(S.catalog).filter(([,c])=>!c.required&&[c.code,c.name,c.interest,c.description].join(' ').toLowerCase().includes(query));
    holder.innerHTML=matches.map(([id,c])=>{const reasons=electiveEligibility(id,slot);return `<article><div><span>${safe(c.code)} · ${c.credits} credits</span><h3>${safe(c.name)}</h3><p>${safe(c.description||'No description available.')}</p><small>${safe(c.interest||'General interest')} · ${safe((c.terms||[]).join(' / ')||'Term not provided')}</small><small>Prerequisites: ${c.prereqs?.length?c.prereqs.map(p=>safe(S.catalog[p]?.code||p)).join(', '):'None in this demo'}</small>${reasons.length?`<p class="degree-picker-warning">${safe(reasons.join(' '))}</p>`:''}</div><div class="degree-picker-actions"><button type="button" class="button quiet" data-degree-action="details" data-course="${safe(id)}">Details</button><button type="button" class="button primary" data-degree-action="select-elective" data-course="${safe(id)}" ${reasons.length?'disabled':''}>Choose course</button></div></article>`;}).join('')||'<div class="empty-state">No matching demo electives. Try a different search.</div>';
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
    return `<article class="advisor-term"><div><h3>${safe(term.label)}</h3><span>${term.past?'Completed':`${futureCredits(term)} cr`}</span></div>${ids.map(id=>`<button type="button" data-degree-action="details" data-course="${safe(id)}"><strong>${safe(S.catalog[id].code)} <small>${S.catalog[id].credits} cr</small></strong><span>${safe(S.catalog[id].name)}</span><em>${safe(courseReason(S.catalog[id]))}</em></button>`).join('')}${slots.map(slot=>{const c=slotFilled(slot);return c?`<button type="button" data-degree-action="details" data-course="${safe(S.state.electives[slot.id])}"><strong>${safe(c.code)} <small>${c.credits} cr</small></strong><span>${safe(c.name)}</span><em>Free elective</em></button>`:`<p class="advisor-empty-slot">${slot.credits}-credit elective · Unfilled</p>`;}).join('')}${!ids.length&&!slots.length?`<p>${term.past?'Recorded in the 82-credit transcript total.':'No courses planned.'}</p>`:''}</article>`;
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
      const button=event.target.closest('[data-degree-action]');if(!button||button.disabled)return;
      const action=button.dataset.degreeAction,S=api();
      if(action==='details')S.openCourse(button.dataset.course);
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
    document.addEventListener('dragstart',event=>{
      const card=event.target.closest('[data-degree-course][draggable="true"]');if(!card)return;
      if(event.target.closest('select')){event.preventDefault();return;}
      const source=termById(card.dataset.degreeFrom);if(!source||source.past||api().completed.has(card.dataset.degreeCourse)){event.preventDefault();return;}
      document.body.classList.add('degree-is-dragging');
      event.dataTransfer.setData('application/syllo-degree',JSON.stringify({id:card.dataset.degreeCourse,from:card.dataset.degreeFrom}));event.dataTransfer.effectAllowed='move';card.classList.add('is-dragging');
    });
    document.addEventListener('dragend',()=>{document.body.classList.remove('degree-is-dragging');document.querySelectorAll('.degree-term.is-drop-target,.degree-course.is-dragging').forEach(el=>el.classList.remove('is-drop-target','is-dragging'))});
    document.addEventListener('dragover',event=>{const term=event.target.closest('.degree-term.is-future');if(!term||!Array.from(event.dataTransfer.types).includes('application/syllo-degree'))return;event.preventDefault();event.dataTransfer.dropEffect='move';term.classList.add('is-drop-target');});
    document.addEventListener('dragleave',event=>{const term=event.target.closest('.degree-term.is-future');if(term&&!term.contains(event.relatedTarget))term.classList.remove('is-drop-target');});
    document.addEventListener('drop',event=>{const term=event.target.closest('.degree-term');if(!term)return;const raw=event.dataTransfer.getData('application/syllo-degree');if(!raw)return;event.preventDefault();document.body.classList.remove('degree-is-dragging');term.classList.remove('is-drop-target');if(term.classList.contains('is-completed')){api().showToast('Completed semester is locked','Move this course into a future semester instead.');return}try{const data=JSON.parse(raw);moveCourse(data.id,data.from,term.dataset.degreeTerm);}catch{/* Ignore unrelated drag data. */}});
    document.addEventListener('keydown',event=>{
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
