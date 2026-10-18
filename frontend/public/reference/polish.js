/* UI refinements over the existing planner. No remote authentication or catalog calls. */
(() => {
  'use strict';
  const S=window.Syllo, safe=S.escape;
  let degreeTab='plan', selected=null, interestQuery={planner:'',requirements:''};
  let advisorFilters=new Set(['All']);
  const statuses=['Done','Review','Withdraw','Planned'];

  // One replaceable matching boundary; no invented course/prerequisite records.
  function searchInterest(query) {
    const words=query.toLowerCase().trim().split(/\s+/).filter(Boolean);
    if(!words.length)return [];
    const aliases={finance:['economics','economic'],security:['systems','network'],machine:['data','statistic'],learning:['data','statistic']};
    return Object.entries(S.catalog).map(([id,c])=>{
      const text=[c.name,c.description,c.interest].join(' ').toLowerCase();
      const score=words.reduce((n,w)=>n+(text.includes(w)?3:(aliases[w]||[]).some(a=>text.includes(a))?1:0),0);
      return {id,c,score};
    }).filter(x=>x.score>0).sort((a,b)=>Number(a.c.required)-Number(b.c.required)||b.score-a.score||a.c.code.localeCompare(b.c.code));
  }
  function interestMarkup(context){
    return `<div class="interest-search" data-interest-context="${context}"><label for="interest-${context}">Search by interest</label><div class="interest-controls"><input type="search" id="interest-${context}" placeholder="Try machine learning, security, or finance" autocomplete="off" aria-controls="interest-results-${context}" aria-expanded="false"><button type="button" data-polish="interest-search">Explore</button></div><div class="interest-results" id="interest-results-${context}" hidden></div></div>`;
  }
  function renderInterest(root){
    const context=root.dataset.interestContext,input=root.querySelector('input'),holder=root.querySelector('.interest-results');
    interestQuery[context]=input.value;
    const query=input.value.trim(),matches=searchInterest(query);
    holder.hidden=!query;input.setAttribute('aria-expanded',String(!!query));
    if(!query)return;
    holder.innerHTML=`<div class="interest-result-heading"><strong>${matches.length} related courses · electives first</strong><button type="button" data-polish="close-interest" aria-label="Close interest results">×</button></div>${matches.map(({id,c})=>{
      const access=S.eligible(id),added=S.state.planner.includes(id);
      const action=context==='planner'?`<button type="button" data-sem-action="add" data-id="${id}" ${!access.ok||added?'disabled':''}>${added?'Added':access.ok?'Add':'Preview only'}</button>`:`<button type="button" data-polish="find-degree-course" data-course="${id}">Choose semester</button>`;
      return `<article ${context==='planner'&&access.ok&&!added?`draggable="true" data-sem-drag="${id}"`:''}><div><strong>${safe(c.code)} · ${safe(c.name)}</strong><span>${c.required?'Requirement':'Elective'} · ${c.credits} credits · ${safe(c.terms.join(' / '))}</span>${!access.ok?`<small>${safe(access.reasons.join(' '))} ${c.prereqs.length?'Complete '+c.prereqs.map(p=>safe(S.catalog[p]?.code||p)).join(', ')+' first.':''}</small>`:''}</div><div class="interest-actions"><button type="button" data-polish="details" data-course="${id}">Details</button>${action}</div></article>`;
    }).join('')||'<p>No matches in this demo catalog. Try data, ethics, economics, or art.</p>'}`;
  }

  function graphData(){
    const levels={},visiting=new Set();
    function level(id){
      if(levels[id]!==undefined)return levels[id];
      if(visiting.has(id))return 0;
      visiting.add(id);
      const parents=S.catalog[id].prereqs.filter(p=>S.catalog[p]);
      const value=parents.length?1+Math.max(...parents.map(level)):0;
      visiting.delete(id);return levels[id]=value;
    }
    Object.keys(S.catalog).forEach(level);
    const columns=Array.from({length:Math.max(...Object.values(levels))+1},()=>[]);
    Object.keys(S.catalog).forEach(id=>columns[levels[id]].push(id));
    return {levels,columns};
  }
  function relatives(id,backwards){
    const found=new Set();
    function walk(current){
      const next=backwards?S.catalog[current].prereqs:Object.keys(S.catalog).filter(k=>S.catalog[k].prereqs.includes(current));
      next.filter(k=>S.catalog[k]&&!found.has(k)&&k!==id).forEach(k=>{found.add(k);walk(k)});
    }
    walk(id);return found;
  }
  function renderMap(){
    const holder=document.querySelector('[data-prerequisite-map]');if(!holder)return;
    const {columns,levels}=graphData(),before=selected?relatives(selected,true):new Set(),after=selected?relatives(selected,false):new Set();
    const related=new Set([selected,...before,...after]);
    const colWidth=228,rowHeight=82,width=columns.length*colWidth,height=Math.max(...columns.map(c=>c.length))*rowHeight+45;
    const position=id=>({x:levels[id]*colWidth+12,y:columns[levels[id]].indexOf(id)*rowHeight+35});
    const edges=selected?Object.entries(S.catalog).flatMap(([to,c])=>c.prereqs.filter(from=>S.catalog[from]&&related.has(from)&&related.has(to)).map(from=>{
      const a=position(from),b=position(to),x=a.x+196,y=a.y+29,tx=b.x,ty=b.y+29;
      return `<path d="M ${x} ${y} C ${x+24} ${y}, ${tx-24} ${ty}, ${tx} ${ty}" marker-end="url(#prereq-arrow)"/>`;
    })).join(''):'';
    holder.innerHTML=`<div class="map-help"><p>${selected?`<strong>${safe(S.catalog[selected].code)}</strong> · ${before.size} prerequisites · ${after.size} unlocked courses`:'Select a course to explore its prerequisites and what it unlocks.'}</p>${selected?`<button type="button" data-polish="details" data-course="${selected}">Course details</button><button type="button" data-polish="clear-map">Clear</button>`:''}</div><div class="map-scroll" tabindex="0" aria-label="Prerequisite flowchart; scroll to explore"><div class="map-canvas" style="width:${width}px;height:${height}px"><svg width="${width}" height="${height}" aria-hidden="true"><defs><marker id="prereq-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M 0 0 L 10 5 L 0 10 z"/></marker></defs><g>${edges}</g></svg>${columns.map((ids,index)=>`<span class="map-column-title" style="left:${index*colWidth+12}px">${index?'Next steps · '+index:'Foundations'}</span>${ids.map(id=>{const c=S.catalog[id],p=position(id);return `<button type="button" class="map-course ${selected===id?'selected':before.has(id)?'prerequisite':after.has(id)?'unlocked':selected?'unrelated':''}" style="left:${p.x}px;top:${p.y}px" data-polish="select-map" data-course="${id}" aria-pressed="${selected===id}"><strong>${safe(c.code)}${S.completed.has(id)?' · Done':''}</strong><span>${safe(c.name)}</span></button>`}).join('')}`).join('')}</div></div><small class="map-disclaimer">Demo prerequisite data, not an official curriculum map. Arrows appear only for the selected path.</small>`;
  }
  function setDegreeTab(tab){
    degreeTab=tab==='ai'?'ai':'plan';
    document.querySelectorAll('[data-degree-panel]').forEach(el=>el.hidden=el.dataset.degreePanel!==tab);
    document.querySelectorAll('[data-polish="degree-tab"]').forEach(b=>{b.setAttribute('aria-selected',String(b.dataset.tab===tab));b.tabIndex=b.dataset.tab===tab?0:-1});
  }
  function highlightDegreeCourses(){
    const calendar=document.querySelector('.degree-semester-calendar');if(!calendar)return;
    const before=selected?relatives(selected,true):new Set(),after=selected?relatives(selected,false):new Set();
    calendar.classList.toggle('has-course-focus',!!selected);
    document.body.classList.toggle('degree-course-focus',!!selected);
    calendar.querySelectorAll('[data-degree-course]').forEach(card=>{
      const id=card.dataset.degreeCourse;
      card.classList.toggle('is-course-selected',id===selected);
      card.classList.toggle('is-course-prerequisite',before.has(id));
      card.classList.toggle('is-course-unlocked',after.has(id));
      card.classList.toggle('is-course-unrelated',!!selected&&id!==selected&&!before.has(id)&&!after.has(id));
      card.querySelector('.degree-course-info')?.setAttribute('aria-pressed',String(id===selected));
    });
    let note=document.getElementById('degree-course-focus-note');
    if(!note){note=document.createElement('div');note.id='degree-course-focus-note';note.className='degree-course-focus-note';note.setAttribute('role','status');document.querySelector('.degree-calendar-scroll').before(note)}
    note.hidden=!selected;
    if(selected)note.innerHTML=`<span class="degree-focus-key"><i aria-hidden="true"></i><strong>${safe(S.catalog[selected].code)}</strong> Selected</span><span class="degree-focus-key before"><i aria-hidden="true"></i>Prerequisites (${before.size})</span><span class="degree-focus-key after"><i aria-hidden="true"></i>Follow-on courses (${after.size})</span><button type="button" data-polish="clear-degree-focus">Clear selection ×</button>`;
  }
  function clearDegreeFocus(closeDrawer=false){
    selected=null;highlightDegreeCourses();
    if(closeDrawer&&document.getElementById('detail-drawer').classList.contains('open'))document.querySelector('[data-action="close-drawer"]')?.click();
  }
  document.addEventListener('syllo:drawer-closed',()=>clearDegreeFocus());
  // The degree page now has one unified search (with an Interest tag) and a floating Syllo AI widget,
  // so it no longer gets the separate interest box or the calendar / AI tabs. Course focus still applies.
  function enhanceDegree(){
    if(!document.querySelector('.degree-shell .degree-semester-calendar'))return;
    highlightDegreeCourses();
  }
  function courseStatus(id){
    return S.completed.has(id)?'Done':S.state.preferences.advisorCourseStatuses?.[id]||(S.state.review.comments.some(c=>c.courseId===id)||S.state.review.status==='submitted'?'Review':'Planned');
  }
  function filterAdvisor(){
    const grid=document.querySelector('.advisor-term-grid');if(!grid)return;
    let count=0;
    grid.querySelectorAll('.advisor-term').forEach(term=>{
      let shown=0;
      term.querySelectorAll('button[data-course]').forEach(button=>{
        const status=courseStatus(button.dataset.course),visible=advisorFilters.has('All')||advisorFilters.has(status);
        button.hidden=!visible;if(visible){shown++;count++}
        let label=button.querySelector('.advisor-course-status');if(!label){label=document.createElement('small');label.className='advisor-course-status';button.append(label)}label.textContent=status;
      });
      term.hidden=!advisorFilters.has('All')&&!shown;
    });
    document.querySelectorAll('[data-advisor-filter]').forEach(b=>b.setAttribute('aria-pressed',String(advisorFilters.has(b.dataset.advisorFilter))));
    document.getElementById('advisor-filter-count').textContent=`${count} courses shown`;
    document.getElementById('advisor-filter-empty').hidden=count!==0;
  }
  function enhanceAdvisor(){
    const grid=document.querySelector('.advisor-term-grid');if(!grid)return;
    if(!document.getElementById('advisor-status-filter')){
      grid.insertAdjacentHTML('beforebegin',`<div id="advisor-status-filter" class="advisor-status-filter"><span>Course status</span><div role="group" aria-label="Filter course statuses">${['All',...statuses].map(status=>`<button type="button" data-advisor-filter="${status}">${status}</button>`).join('')}</div><small id="advisor-filter-count" aria-live="polite"></small></div><p id="advisor-filter-empty" hidden>No courses match these statuses. Choose All to reset.</p>`);
      if(S.state.role==='advisor')grid.insertAdjacentHTML('beforebegin',`<details class="advisor-status-editor"><summary>Update a planning status</summary><p>Demo review labels only; this does not withdraw or change a transcript.</p><label>Course <select id="advisor-status-course">${Object.entries(S.catalog).filter(([id])=>!S.completed.has(id)&&S.terms.some(t=>(S.state.degree[t.id]||[]).includes(id))||Object.values(S.state.electives).includes(id)).map(([id,c])=>`<option value="${id}">${safe(c.code)} · ${safe(c.name)}</option>`).join('')}</select></label><label>Status <select id="advisor-status-value">${statuses.filter(s=>s!=='Done').map(s=>`<option>${s}</option>`).join('')}</select></label><button type="button" data-polish="save-course-status">Save label</button></details>`);
    }
    filterAdvisor();
  }
  function enhance(){
    enhanceDegree();enhanceAdvisor();
    // Plan My Semester handles course and interest matching in its single course-search field.
    document.querySelectorAll('.planner-sidebar-course:not(.planned)').forEach(card=>{
      const link=card.querySelector('[data-sem-action="details"]'),c=S.catalog[link?.dataset.id];
      if(c){card.title=`${c.name} · ${c.credits} credits · ${c.requirement} · ${c.days} ${c.time}. Open details for prerequisites.`;card.querySelector('small')?.setAttribute('aria-hidden','true')}
    });
  }
  document.addEventListener('input',e=>{if(e.target.matches('.interest-search input'))renderInterest(e.target.closest('.interest-search'))});
  document.addEventListener('keydown',e=>{
    if(e.key==='Enter'&&e.target.matches('.interest-search input')){e.preventDefault();renderInterest(e.target.closest('.interest-search'))}
    if(e.key==='Escape'){clearDegreeFocus();document.querySelectorAll('.interest-results').forEach(el=>el.hidden=true);document.querySelectorAll('.interest-search input').forEach(el=>el.setAttribute('aria-expanded','false'))}
    if(e.target.matches('[data-polish="degree-tab"]')&&['ArrowLeft','ArrowRight','Home','End'].includes(e.key)){
      e.preventDefault();const tabs=['plan','ai'],i=tabs.indexOf(degreeTab),next=e.key==='Home'?0:e.key==='End'?1:(i+1)%2;clearDegreeFocus(true);setDegreeTab(tabs[next]);document.getElementById('degree-tab-'+tabs[next]).focus();
    }
  });
  document.addEventListener('click',e=>{
    const button=e.target.closest('[data-polish]'),filter=e.target.closest('[data-advisor-filter]');
    if(filter){const value=filter.dataset.advisorFilter;if(value==='All')advisorFilters=new Set(['All']);else{advisorFilters.delete('All');advisorFilters.has(value)?advisorFilters.delete(value):advisorFilters.add(value);if(!advisorFilters.size)advisorFilters.add('All')}filterAdvisor();return}
    if(button){
      const action=button.dataset.polish;
      if(action==='degree-tab'){clearDegreeFocus(true);setDegreeTab(button.dataset.tab)}
      else if(action==='clear-degree-focus')clearDegreeFocus(true);
      else if(action==='interest-search')renderInterest(button.closest('.interest-search'));
      else if(action==='close-interest'){button.closest('.interest-results').hidden=true;const input=button.closest('.interest-search').querySelector('input');input.setAttribute('aria-expanded','false');input.focus()}
      else if(action==='details')S.openCourse(button.dataset.course);
      else if(action==='select-map'){selected=selected===button.dataset.course?null:button.dataset.course;renderMap();document.querySelector(`[data-polish="select-map"][data-course="${button.dataset.course}"]`)?.focus()}
      else if(action==='clear-map'){selected=null;renderMap()}
      else if(action==='find-degree-course'){
        const input=document.getElementById('degree-course-query');input.value=S.catalog[button.dataset.course].code;input.dispatchEvent(new Event('input',{bubbles:true}));button.closest('.interest-results').hidden=true;input.focus();
      }else if(action==='save-course-status'&&S.state.role==='advisor'){
        const id=document.getElementById('advisor-status-course').value,status=document.getElementById('advisor-status-value').value;
        if(id){S.change('Update advisor planning status',state=>{state.preferences.advisorCourseStatuses={...state.preferences.advisorCourseStatuses,[id]:status};state.review.notifications.push({id:'status-'+Date.now(),text:`Your advisor marked ${S.catalog[id].code} ${status.toLowerCase()} in the demo plan.`,read:false,createdAt:new Date().toISOString()})});S.showToast('Planning label saved','This does not alter registration or completed coursework.')}
      }
    }else if(e.target.closest('.map-canvas')){selected=null;renderMap()}
    if(!e.target.closest('.interest-search')){document.querySelectorAll('.interest-results').forEach(el=>el.hidden=true);document.querySelectorAll('.interest-search input').forEach(el=>el.setAttribute('aria-expanded','false'))}
  });
  document.addEventListener('click',e=>{
    const cardButton=e.target.closest('.degree-semester-calendar .degree-course-info');
    if(cardButton&&e.shiftKey)return; // Shift+click selects several courses (degree.js), it doesn't focus one.
    if(cardButton){
      const id=cardButton.dataset.course;
      if(selected===id){e.preventDefault();e.stopImmediatePropagation();clearDegreeFocus(true)}
      else{selected=id;highlightDegreeCourses()}
      return;
    }
    if(e.target.closest('[data-action="close-drawer"],#drawer-backdrop,#product-nav,[data-action="back-features"]'))clearDegreeFocus();
    else if(e.target.closest('.degree-calendar-scroll')&&!e.target.closest('.degree-course,button,select,label'))clearDegreeFocus(true);
  },true);
  // Existing renderers rebuild their own sections. Reapply enhancements once after each change.
  const observer=new MutationObserver(()=>enhance());
  observer.observe(document.querySelector('.degree-shell'),{childList:true});
  observer.observe(document.querySelector('.advisor-shell'),{childList:true});
  S.subscribe(enhance);
  window.SylloPolish={searchInterest,graphData,courseStatus};
  enhance();
})();
