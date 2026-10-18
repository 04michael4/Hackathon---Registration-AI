/* Application navigation, shared course drawer and saved schedule library. */
(() => {
  'use strict';
  const S=window.Syllo,$=id=>document.getElementById(id),safe=S.escape;
  let toastTimer,activeScreen='login';
  function showToast(title,detail=''){
    const toast=$('toast');toast.querySelector('strong').textContent=title;toast.querySelector('small').textContent=detail;
    toast.classList.add('show');clearTimeout(toastTimer);toastTimer=setTimeout(()=>toast.classList.remove('show'),4500);
  }
  function closeDrawer(){$('detail-drawer').classList.remove('open');$('detail-drawer').setAttribute('aria-hidden','true');$('drawer-backdrop').classList.add('hidden');document.body.classList.remove('degree-details-open');document.dispatchEvent(new Event('syllo:drawer-closed'))}
  function openCourse(id){
    const c=S.catalog[id];if(!c)return;
    $('drawer-pill').textContent=c.code;$('drawer-lock').hidden=true;
    const check=S.eligible(id),prereqs=c.prereqs.map(pre=>S.catalog[pre]?.code||pre).join(', ')||'None in demo';
    const row=(label,value)=>`<div class="course-info-row"><span>${safe(label)}</span><strong>${safe(value)}</strong></div>`;
    $('drawer-content').innerHTML=`<h2>${safe(c.name)}</h2><p class="subtitle">${safe(c.code)} · Demo catalog</p><p class="course-description">${safe(c.description)}</p><section class="detail-section"><h3>Course & section</h3>${row('Credits',c.credits)}${row('Requirement',c.requirement)}${row('Prerequisites',prereqs)}${row('Terms offered',c.terms.join(' / '))}${row('Section',c.section)}${row('Meetings',c.days&&c.time?`${c.days} · ${c.time}`:'Section time unavailable')}${row('Next-semester eligibility',check.ok?'Prerequisites met in demo record':check.reasons.join(' '))}</section><section class="detail-section"><h3>Professor & workload</h3>${row('Professor',c.section.includes('·')?c.section.split('·').slice(1).join('·').trim():'Not provided')}${row('Professor rating',c.rating?`${c.rating} / 5`:'Not provided')}${row('Course difficulty',c.difficulty)}${row('Weekly workload',c.workload)}<p class="sample-note">Simulated course details, professors and ratings. No live registration or seat availability.</p></section>`;
    document.body.classList.toggle('degree-details-open',activeScreen==='requirements');
    $('detail-drawer').classList.add('open');$('detail-drawer').setAttribute('aria-hidden','false');$('drawer-backdrop').classList.remove('hidden');$('detail-drawer').querySelector('[data-action="close-drawer"]').focus();
  }
  // Real API courses never enter the browser-local demo catalog or saved plans.
  document.addEventListener('syllo:catalog-open',event=>{
    const {code,term}=event.detail||{};
    if(document.documentElement.dataset.sylloCatalog!=='enabled'||typeof code!=='string'||!(/^[A-Z]+-[0-9]+(?:-[0-9]+)?$/).test(code)||typeof term!=='string'||!(/^[0-9]{4}-[A-Z]+$/).test(term))return;
    $('drawer-pill').textContent=code;$('drawer-lock').hidden=true;
    $('drawer-content').innerHTML=`<div data-catalog-detail="${safe(code)}" data-catalog-term="${safe(term)}"></div>`;
    document.body.classList.remove('degree-details-open');
    $('detail-drawer').classList.add('open');$('detail-drawer').setAttribute('aria-hidden','false');$('drawer-backdrop').classList.remove('hidden');
    $('detail-drawer').querySelector('[data-action="close-drawer"]').focus();
  });
  function renderNotifications(){
    const notices=S.state.review.notifications.filter(item=>!item.read),badge=$('advisor-alert'),banner=$('feedback-notification');
    badge.textContent=notices.length;badge.classList.toggle('hidden',!notices.length||S.state.role==='advisor');
    $('role-switch').textContent=S.state.role==='advisor'?'Advisor demo ⇄':'Student demo ⇄';
    banner.classList.toggle('hidden',!notices.length||S.state.role==='advisor'||activeScreen==='login');
    if(notices.length)banner.innerHTML=`<div><strong>New advisor feedback</strong><span>${safe(notices.at(-1).text)}</span></div><button data-action="open-advisor">View feedback →</button><button data-action="dismiss-feedback" aria-label="Mark advisor updates as read">×</button>`;
  }
  function navigate(screen){
    // The old "What would you like to do?" hub is gone: anything that pointed to it opens Plan My Semester.
    if(!['login','planner','requirements','advisor','saved'].includes(screen))screen='planner';
    if(screen!=='planner')window.SylloPlanner?.clearSectionPreview?.();
    activeScreen=screen;closeDrawer();
    document.querySelectorAll('[data-screen]').forEach(element=>element.classList.toggle('active',element.dataset.screen===screen));
    document.body.classList.toggle('welcome-only',screen==='login');$('product-nav').classList.toggle('hidden',screen==='login');
    document.body.dataset.activeScreen=screen;
    document.querySelectorAll('[data-route]').forEach(button=>{const active=button.dataset.route===screen;button.classList.toggle('active',active);if(active)button.setAttribute('aria-current','page');else button.removeAttribute('aria-current')});
    if(screen==='planner')window.SylloPlanner?.render();
    if(screen==='requirements'){S.state.preferences.hiddenDegreeTerms=[];S.persist();window.SylloDegree.render()}
    if(screen==='advisor')window.SylloDegree.renderAdvisor();
    if(screen==='saved')renderSaved();
    renderNotifications();window.scrollTo({top:0});
  }
  function renderSaved(){
    if(document.documentElement.dataset.sylloMode==='api')return;
    $('saved-list').innerHTML=S.state.saved.length?S.state.saved.map(item=>{
      const ids=item.courses.filter(id=>S.catalog[id]),credit=ids.reduce((sum,id)=>sum+S.catalog[id].credits,0);
      return `<article class="saved-card"><div class="saved-card-kicker"><span>SPRING 2027</span><button data-action="delete-saved" data-id="${safe(item.id)}" aria-label="Delete ${safe(item.name)}">×</button></div><h2>${safe(item.name)}</h2><p>${ids.map(id=>safe(S.catalog[id].code)).join(' · ')||'Empty schedule'}</p><div class="saved-card-meta"><strong>${credit} credits · ${ids.length} courses</strong><time>${safe(new Date(item.savedAt).toLocaleString('en-US',{month:'short',day:'numeric',hour:'numeric',minute:'2-digit'}))}</time></div><button class="button primary" data-action="load-saved" data-id="${safe(item.id)}">Open schedule →</button><button class="saved-rename" data-action="rename-saved" data-id="${safe(item.id)}">Rename</button></article>`;
    }).join(''):'<div class="saved-empty"><span>↗</span><h2>A good week is worth saving.</h2><p>Build your schedule, choose Save schedule, and find it here.</p><button class="button primary" data-action="open-schedules">Build your first plan →</button></div>';
  }
  function syncDegreeToCalendar(){
    if(document.documentElement.dataset.sylloMode==='api'){showToast('Sample degree workspace','Select the matching live catalog courses in Plan My Semester. Degree and transcript APIs are not connected.');navigate('planner');return;}
    const ids=(S.state.degree[S.nextTerm]||[]).filter(id=>S.eligible(id).ok),blocked=(S.state.degree[S.nextTerm]||[]).filter(id=>!S.eligible(id).ok);
    S.change('Use degree plan in semester calendar',state=>{state.planner=ids});navigate('planner');
    showToast('Degree courses added to your calendar',blocked.length?`Preview only: ${blocked.map(id=>S.catalog[id].code).join(', ')}. Check their prerequisites or section details.`:'Review the meeting times and save your schedule.');
  }
  function syncCalendarToDegree(){
    if(document.documentElement.dataset.sylloMode==='api'){showToast('Sample degree workspace','Live semester plans are separate from the sample degree plan.');return;}
    const next=S.nextTerm,later=S.terms.find(t=>!t.past&&t.id!==next).id,candidate=JSON.parse(JSON.stringify(S.state.degree)),ids=S.state.planner;
    const deferred=candidate[next].filter(id=>!ids.includes(id));
    S.terms.filter(t=>!t.past).forEach(t=>{candidate[t.id]=candidate[t.id].filter(id=>!ids.includes(id))});
    candidate[next]=[...ids];candidate[later]=[...new Set([...candidate[later],...deferred])];
    const reasons=S.sequenceIssues(candidate);
    if(reasons.length){showToast('Review your degree sequence first',reasons.slice(0,2).join(' '));return}
    S.change('Use semester calendar in degree plan',state=>{state.degree=candidate;S.slots.forEach(slot=>{if(ids.includes(state.electives[slot.id]))state.electives[slot.id]=null})});
    navigate('requirements');showToast('Semester schedule added to degree plan','Other required courses remain planned in future semesters. Review the graduation checks.');
  }
  // The navigation ribbon is always visible; no hover state, menu or collapse timers.
  const nav=$('product-nav');
  nav.classList.remove('is-collapsible','is-open');
  nav.classList.add('is-persistent');
  $('product-nav-toggle')?.remove();
  S.showToast=showToast;S.openCourse=openCourse;S.navigate=navigate;
  document.addEventListener('click',event=>{
    const button=event.target.closest('[data-action]');if(!button||button.disabled)return;
    const action=button.dataset.action;
    if(action==='login'||action==='advisor-login'){S.state.role=action==='login'?'student':'advisor';S.persist();navigate(action==='login'?'planner':'advisor')}
    else if(action==='switch-role'){S.state.role=S.state.role==='student'?'advisor':'student';S.persist();S.emit();navigate(S.state.role==='advisor'?'advisor':'planner');showToast(S.state.role==='advisor'?'Advisor demo':'Student demo','Both roles use the same browser demo plan.')}
    else if(action==='open-schedules')navigate('planner');
    else if(action==='open-degree')navigate('requirements');
    else if(action==='open-advisor')navigate('advisor');
    else if(action==='open-saved')navigate('saved');
    else if(action==='back-features')navigate('planner');
    else if(action==='close-drawer')closeDrawer();
    else if(action==='dismiss-feedback'){S.state.review.notifications.forEach(item=>item.read=true);S.persist();S.emit()}
    else if(action==='degree-to-calendar')syncDegreeToCalendar();
    else if(action==='calendar-to-degree')syncCalendarToDegree();
    else if(action==='load-saved'){
      const saved=S.state.saved.find(item=>item.id===button.dataset.id);if(!saved)return;
      S.change(`Open ${saved.name}`,state=>{state.planner=[...saved.courses];if(saved.preferences)state.preferences={...state.preferences,...saved.preferences}});navigate('planner');showToast('Saved schedule opened',saved.name);
    } else if(action==='delete-saved'){
      S.change('Delete saved schedule',state=>{state.saved=state.saved.filter(item=>item.id!==button.dataset.id)});showToast('Saved copy removed','Use Undo to restore it.');
    } else if(action==='rename-saved'){
      const saved=S.state.saved.find(item=>item.id===button.dataset.id);if(!saved)return;
      const dialog=$('saved-name-dialog');dialog.dataset.id=saved.id;$('saved-name-input').value=saved.name;dialog.showModal();$('saved-name-input').select();
    } else if(action==='cancel-rename')$('saved-name-dialog').close();
  });
  $('drawer-backdrop').addEventListener('click',closeDrawer);
  document.addEventListener('keydown',event=>{
    if(event.key==='Escape')closeDrawer();
    // Ctrl/⌘+Z undoes and Ctrl/⌘+Shift+Z (or Ctrl+Y) redoes. The semester planner handles its own keys.
    const historyKey=(event.ctrlKey||event.metaKey)&&activeScreen!=='planner'&&activeScreen!=='login'&&!event.target.closest('input,textarea,select,[contenteditable="true"]');
    if(historyKey&&event.key.toLowerCase()==='z'){event.preventDefault();if(event.shiftKey)S.redo();else S.undo()}
    else if(historyKey&&event.ctrlKey&&!event.metaKey&&event.key.toLowerCase()==='y'){event.preventDefault();S.redo()}
    if(event.key==='Tab'&&$('detail-drawer').classList.contains('open')&&!document.body.classList.contains('degree-details-open')){const elements=[...$('detail-drawer').querySelectorAll('button:not([hidden]),a,input')],first=elements[0],last=elements.at(-1);if(event.shiftKey&&document.activeElement===first){event.preventDefault();last.focus()}else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first.focus()}}
  });
  $('saved-name-form').addEventListener('submit',event=>{event.preventDefault();const name=$('saved-name-input').value.trim(),id=$('saved-name-dialog').dataset.id;if(!name)return;S.change('Rename saved schedule',state=>{const item=state.saved.find(item=>item.id===id);if(item)item.name=name.slice(0,80)});$('saved-name-dialog').close()});
  S.subscribe(()=>{renderNotifications();renderSaved()});
  window.SylloPlanner?.init();window.SylloDegree.init();window.SylloAIWidget.init();document.body.dataset.activeScreen=activeScreen;
  renderSaved();renderNotifications();
})();
