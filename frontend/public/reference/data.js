/* Syllo's browser-only demo model. Replace this boundary with backend data later.
 * Course names, sections, professors, rules, ratings and dates are simulated.
 * Nothing here is an official Lehigh catalog or an academic eligibility decision.
 */
(() => {
  'use strict';
  const course = (code,name,credits,requirement,prereqs,terms,extra={}) => ({code,name,credits,requirement,prereqs,terms,required:true,section:'Not provided',days:'',time:'',rating:null,difficulty:'Not provided',workload:'Not provided',description:'Description not provided in this demo record.',color:'var(--theme-brown)',bg:'var(--theme-tint)',interest:'Computing',...extra});
  const catalog = {
    cse007:course('CSE 007','Introduction to Programming',4,'Major foundation',[],['fall','spring']),
    cse017:course('CSE 017','Programming & Data Structures',4,'Major foundation',['cse007'],['fall','spring']),
    math022:course('MATH 022','Calculus II',4,'Mathematics foundation',[],['fall','spring'],{interest:'Mathematics'}),
    cse241:course('CSE 241','Data Structures & Algorithms',4,'CS Major Core',['cse017'],['fall','spring'],{section:'010 · Prof. Maya Chen',days:'Mon / Wed',time:'9:10–10:25 AM',rating:4.6,difficulty:'3.7 / 5',workload:'8–10 hrs/wk',description:'Demo course covering data structures and algorithm analysis.'}),
    math231:course('MATH 231','Probability & Statistics',4,'Data Science Minor',['math022'],['fall','spring'],{section:'020 · Prof. Daniel Park',days:'Tue / Thu',time:'10:45 AM–12:00 PM',color:'var(--theme-brown)',bg:'var(--theme-tint)',rating:4.3,difficulty:'3.3 / 5',workload:'6–8 hrs/wk',interest:'Mathematics',description:'Demo course exploring probability models and statistical inference.'}),
    ds210:course('DS 210','Data Systems',3,'Data Science Minor',['cse017'],['fall','spring'],{section:'010 · Prof. Elena Ruiz',days:'Tue / Thu',time:'1:10–2:25 PM',color:'var(--theme-brown)',bg:'var(--theme-tint)',rating:4.8,difficulty:'3.9 / 5',workload:'8–11 hrs/wk',description:'Demo course exploring how data is stored, queried, and organized.'}),
    phil114:course('PHIL 114','Ethics of Technology',3,'General education · Humanities',[],['fall','spring'],{section:'011 · Prof. Samira Cole',days:'Mon / Wed',time:'11:15 AM–12:05 PM',color:'var(--theme-brown)',bg:'var(--theme-tint)',rating:4.5,difficulty:'2.4 / 5',workload:'3–5 hrs/wk',interest:'Technology & society',description:'Demo seminar on ethical questions raised by new technologies.'}),
    cse109:course('CSE 109','Systems Software',4,'CS Major Core',['cse017'],['fall','spring'],{section:'010 · Prof. Evan Cole',days:'Mon / Wed',time:'8:00–9:00 AM',color:'var(--theme-brown)',bg:'var(--theme-tint)',rating:4.7,difficulty:'3.8 / 5',workload:'8–10 hrs/wk',description:'Demo systems course. The only sample section begins at 8 AM.'}),
    cse252:course('CSE 252','Computer Networks Lab',1,'CS Major Core',['cse109'],['fall','spring'],{section:'013 · Prof. Jordan Lee',days:'Thursday',time:'2:35–4:25 PM',color:'var(--theme-brown)',bg:'var(--theme-tint)',rating:4.2,difficulty:'3.1 / 5',workload:'3–4 hrs/wk',description:'Demo networking laboratory; complete Systems Software first.'}),
    cse202:course('CSE 202','Software Design',3,'CS Major Core',['cse241'],['fall','spring'],{section:'010 · Prof. Maya Chen',days:'Mon / Wed',time:'1:10–2:25 PM',rating:4.6,description:'Demo course on software design and testing.'}),
    cse303:course('CSE 303','Computing Theory',3,'CS Major Core',['cse241'],['fall','spring'],{section:'010 · Prof. Jordan Lee',days:'Tue / Thu',time:'9:20–10:35 AM',rating:4.2,description:'Demo theory course. Data Structures & Algorithms must be completed first.'}),
    cse398:course('CSE 398','Senior Project I',3,'Major capstone',['cse109'],['fall'],{description:'Demo first capstone course, offered in fall only.'}),
    cse399:course('CSE 399','Senior Project II',3,'Major capstone',['cse398'],['spring'],{description:'Demo second capstone course, offered in spring after Senior Project I.'}),
    eco001:course('ECO 001','Principles of Economics',4,'Free elective',[],['fall','spring'],{required:false,section:'020 · Prof. Riley Shah',days:'Tue / Thu',time:'12:10–1:25 PM',color:'var(--theme-brown)',bg:'var(--theme-tint)',rating:4.4,difficulty:'2.6 / 5',workload:'4–6 hrs/wk',interest:'Economics',description:'Demo introduction to economic reasoning. Its sample time overlaps DS 210.'}),
    art003:course('ART 003','Visual Thinking',3,'Free elective',[],['fall','spring'],{required:false,section:'010 · Prof. Morgan Lee',days:'Friday',time:'10:00 AM–12:30 PM',color:'var(--theme-brown)',bg:'var(--theme-tint)',rating:4.7,difficulty:'2.1 / 5',workload:'3–4 hrs/wk',interest:'Arts',description:'Demo studio course exploring visual communication.'}),
    phil120:course('PHIL 120','Ethics & Society',3,'Free elective',[],['fall','spring'],{required:false,section:'020 · Prof. Samira Cole',days:'Mon / Wed',time:'2:35–3:50 PM',color:'var(--theme-brown)',bg:'var(--theme-tint)',rating:4.5,difficulty:'2.4 / 5',workload:'3–5 hrs/wk',interest:'Technology & society',description:'Demo elective in ethical reasoning and society.'}),
    ds310:course('DS 310','Applied Data Projects',3,'Free elective',['ds210'],['spring'],{required:false,section:'030 · Prof. Elena Ruiz',days:'Tue / Thu',time:'3:00–4:15 PM',color:'var(--theme-brown)',bg:'var(--theme-tint)',rating:4.8,interest:'Computing',description:'Demo advanced elective. Complete DS 210 in an earlier semester before taking this course.'}),
    // Degree-planner demo additions: an Economics minor and general-education electives (no next-semester sections).
    eco105:course('ECO 105','Intermediate Microeconomics',3,'Economics Minor',['eco001'],['fall','spring'],{required:false,interest:'Economics',description:'Demo course on consumer and producer choice. Take ECO 001 first.'}),
    eco146:course('ECO 146','Applied Econometrics',3,'Economics Minor',['eco001','math231'],['spring'],{required:false,interest:'Economics',description:'Demo course applying statistics to economic data. Take ECO 001 and MATH 231 first.'}),
    soc001:course('SOC 001','Introduction to Sociology',4,'General education elective',[],['fall','spring'],{required:false,interest:'Society',description:'Demo survey of how groups, institutions and cultures shape everyday life.'}),
    psy001:course('PSY 001','Introduction to Psychology',4,'General education elective',[],['fall','spring'],{required:false,interest:'Society',description:'Demo introduction to the science of mind and behavior.'}),
    mus001:course('MUS 001','University Choir',1,'Free elective',[],['fall','spring'],{required:false,interest:'Arts',description:'Demo one-credit ensemble. Shows that an elective can be any number of credits.'})
  };
  // Demo minors and concentrations a student can add from the degree search; their courses go to the staging bucket.
  const programs={
    'ds-minor':{name:'Data Science minor',kind:'minor',courses:['math231','ds210']},
    'econ-minor':{name:'Economics minor',kind:'minor',courses:['eco001','eco105','eco146']},
    'ethics-tech':{name:'Ethics & Technology concentration',kind:'concentration',courses:['phil114','phil120']}
  };
  // Elective requirements tracked by credits rather than by a fixed course (demo rules).
  const requirementGroups=[
    {id:'humanities',label:'Humanities electives',credits:8,category:'elective',courses:['phil114','phil120','art003']},
    {id:'gened',label:'General education electives',credits:8,category:'gened',courses:['soc001','psy001','eco001']}
  ];
  // Other demo sections offered next semester (simulated), used by "Preview other sections".
  // Each lists every meeting that comes with it. CSE 109 keeps a single section on purpose.
  const lecture=(days,time)=>({component:'Lecture',days,time});
  const alternatives={
    cse241:[
      {section:'011',instructor:'Prof. Aaron Brooks',status:'Open',rating:3.8,difficulty:'3.2 / 5',meetings:[lecture('Tue / Thu','1:10–2:25 PM'),{component:'Lab',days:'Friday',time:'9:00–10:50 AM'}]},
      {section:'012',instructor:'Prof. Maya Chen',status:'Waitlist',rating:4.6,difficulty:'3.7 / 5',meetings:[lecture('Mon / Wed','2:35–3:50 PM'),{component:'Lab',days:'Friday',time:'1:10–3:00 PM'}]}
    ],
    math231:[{section:'021',instructor:'Prof. Lena Ortiz',status:'Open',rating:2.9,difficulty:'4.1 / 5',meetings:[lecture('Mon / Wed','3:00–4:15 PM'),{component:'Recitation',days:'Friday',time:'11:00–11:50 AM'}]}],
    ds210:[{section:'011',instructor:'Prof. Elena Ruiz',status:'Closed',rating:4.8,difficulty:'3.9 / 5',meetings:[lecture('Mon / Wed','10:45 AM–12:00 PM')]}],
    phil114:[{section:'010',instructor:'Prof. Noah Bennett',status:null,rating:null,difficulty:null,meetings:[lecture('Tue / Thu','9:20–10:10 AM')]}],
    eco001:[{section:'010',instructor:'Prof. Riley Shah',status:'Open',rating:4.4,difficulty:'2.6 / 5',meetings:[lecture('Mon / Wed','9:10–10:00 AM'),{component:'Recitation',days:'Thursday',time:'4:10–5:00 PM'}]}],
    phil120:[{section:'010',instructor:'Prof. Samira Cole',status:'Open',rating:4.5,difficulty:'2.4 / 5',meetings:[lecture('Tue / Thu','2:35–3:50 PM')]}]
  };
  Object.entries(alternatives).forEach(([id,list])=>{catalog[id].alternatives=list});
  const completed = new Set(['cse007','cse017','math022']);
  const terms = [2024,2025,2026,2027].flatMap(year => [{id:`fall-${year}`,label:`Fall ${year}`,season:'fall',year,past:year<2027},{id:`spring-${year+1}`,label:`Spring ${year+1}`,season:'spring',year:year+1,past:year+1<2027}]);
  // Two free-elective slots. Any number of credits is fine; the semester total is what's capped.
  const slots=[{id:'elective-a',term:'spring-2028'},{id:'elective-b',term:'spring-2028'}];
  const MAX_TERM_CREDITS=21;
  const defaults={version:3,role:'student',planner:['cse241','math231'],degree:{'fall-2024':['cse007'],'spring-2025':['cse017','math022'],'fall-2025':[],'spring-2026':[],'fall-2026':[],'spring-2027':['cse241','math231','ds210','cse109'],'fall-2027':['cse202','cse303','cse398','cse252'],'spring-2028':['cse399','phil114']},electives:{'elective-a':null,'elective-b':null},saved:[],bucket:[],minors:['ds-minor'],review:{status:'draft',comments:[],notifications:[]},preferences:{start:540,end:1020,dayOff:'Fri',professor:'',minCredits:12,maxCredits:16,seeds:['cse241','math231'],aiOpen:false},chat:[],draft:''};
  const clone = value => JSON.parse(JSON.stringify(value));
  const storageKey='syllo-planning-v3';
  let state=clone(defaults),storageAvailable=true;
  try {
    const stored=JSON.parse(localStorage.getItem(storageKey)||'null');
    if(stored?.version===3){
      state={...state,...stored,preferences:{...defaults.preferences,...stored.preferences},review:{...defaults.review,...stored.review}};
      state.planner=Array.isArray(state.planner)?[...new Set(state.planner.filter(id=>catalog[id]))]:defaults.planner;
      state.degree=Object.fromEntries(terms.map(term=>[term.id,Array.isArray(state.degree?.[term.id])?state.degree[term.id].filter(id=>catalog[id]):clone(defaults.degree[term.id])]));
      state.electives=Object.fromEntries(slots.map(slot=>[slot.id,catalog[state.electives?.[slot.id]]?state.electives[slot.id]:null]));
      state.saved=Array.isArray(state.saved)?state.saved.filter(item=>item&&Array.isArray(item.courses)).map(item=>({...item,courses:item.courses.filter(id=>catalog[id])})):[];
      if(!Array.isArray(state.review.comments))state.review.comments=[];
      if(!Array.isArray(state.review.notifications))state.review.notifications=[];
      if(!Array.isArray(state.chat))state.chat=[];
      state.bucket=Array.isArray(state.bucket)?[...new Set(state.bucket.filter(id=>catalog[id]))]:[];
      state.minors=Array.isArray(state.minors)?state.minors.filter(id=>programs[id]):clone(defaults.minors);
      if(!['student','advisor'].includes(state.role))state.role='student';
    }
  } catch(error){ storageAvailable=false; }
  const listeners=new Set(),history=[],future=[];
  const escape = value => String(value??'').replace(/[&<>"']/g,char=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[char]));
  function persist(){try{localStorage.setItem(storageKey,JSON.stringify(state));storageAvailable=true;return true}catch(error){storageAvailable=false;return false}}
  function emit(){listeners.forEach(fn=>fn())}
  function planSnapshot(){return clone({planner:state.planner,degree:state.degree,electives:state.electives,saved:state.saved,preferences:state.preferences,bucket:state.bucket,minors:state.minors})}
  function change(label,mutator){
    const before=planSnapshot(),degreeBefore=JSON.stringify([state.degree,state.electives]);
    mutator(state);
    if(JSON.stringify(before)!==JSON.stringify(planSnapshot())){
      history.push({label,snapshot:before});if(history.length>50)history.shift();
      future.length=0;
      if(degreeBefore!==JSON.stringify([state.degree,state.electives]))state.review.status='draft';
    }
    persist();emit();
  }
  function undo(){if(!history.length)return false;const previous=history.pop(),degreeBefore=JSON.stringify([state.degree,state.electives]);future.push({label:previous.label,snapshot:planSnapshot()});Object.assign(state,previous.snapshot);if(degreeBefore!==JSON.stringify([state.degree,state.electives]))state.review.status='draft';persist();emit();S.showToast('Change undone',previous.label);return true}
  function redo(){if(!future.length)return false;const next=future.pop(),degreeBefore=JSON.stringify([state.degree,state.electives]);history.push({label:next.label,snapshot:planSnapshot()});Object.assign(state,next.snapshot);if(degreeBefore!==JSON.stringify([state.degree,state.electives]))state.review.status='draft';persist();emit();S.showToast('Change redone',next.label);return true}
  function timeValue(value,period){const match=value.trim().match(/(\d+):(\d+)\s*(AM|PM)?/i);if(!match)return null;return (Number(match[1])%12+((match[3]||period||'AM').toUpperCase()==='PM'?12:0))*60+Number(match[2])}
  function timeRange(id){const c=typeof id==='string'?catalog[id]:id;if(!c?.time)return null;const parts=c.time.split('–');if(parts.length!==2)return null;const period=parts[1].match(/AM|PM/i)?.[0];return[timeValue(parts[0],period),timeValue(parts[1],period)]}
  function days(id){return (typeof id==='string'?catalog[id]:id)?.days?.split('/').map(day=>day.trim().slice(0,3)).filter(Boolean)||[]}
  function conflicts(ids){const result=[];ids.forEach((id,index)=>ids.slice(index+1).forEach(other=>{const a=timeRange(id),b=timeRange(other);if(a&&b&&a[0]<b[1]&&b[0]<a[1]&&days(id).some(day=>days(other).includes(day)))result.push([id,other])}));return result}
  function eligible(id,term='spring-2027'){
    const c=catalog[id],t=terms.find(item=>item.id===term),reasons=[];
    if(!c||!t)return{ok:false,reasons:['Course or semester not found.']};
    if(completed.has(id))reasons.push('Already completed in the demo record.');
    if(!c.terms.includes(t.season))reasons.push(`Offered in ${c.terms.join(' and ')} only in the demo catalog.`);
    c.prereqs.filter(prereq=>!completed.has(prereq)).forEach(prereq=>reasons.push(`Complete ${catalog[prereq]?.code||prereq} before ${c.code}.`));
    if(term==='spring-2027'&&!timeRange(id))reasons.push('A next-semester section time is not available.');
    return{ok:reasons.length===0,reasons};
  }
  const requiredIds=Object.keys(catalog).filter(id=>catalog[id].required&&!completed.has(id));
  function sequenceIssues(degree){
    const issues=[],seen=new Set();
    terms.forEach((term,index)=>(degree[term.id]||[]).forEach(id=>{
      const c=catalog[id];if(!c)return;
      if(seen.has(id))issues.push(`${c.code} appears more than once.`);seen.add(id);
      if(term.past)return;
      if(!c.terms.includes(term.season))issues.push(`${c.code} is offered in ${c.terms.join(' and ')} only.`);
      c.prereqs.forEach(prereq=>{if(completed.has(prereq))return;const earlier=terms.slice(0,index).some(t=>(degree[t.id]||[]).includes(prereq));if(!earlier)issues.push(`${c.code} must be in a later semester than ${catalog[prereq]?.code||prereq}.`)});
    }));return issues;
  }
  const effectiveSlots=()=>slots.map(slot=>({...slot,term:state.preferences.degreeElectiveTerms?.[slot.id]||slot.term}));
  /** Credits planned in a semester: its courses plus any elective-slot course placed there. */
  function termCredits(termId,degree=state.degree,electives=state.electives){
    return (degree[termId]||[]).reduce((sum,id)=>sum+(catalog[id]?.credits||0),0)+effectiveSlots().filter(slot=>slot.term===termId).reduce((sum,slot)=>sum+(catalog[electives[slot.id]]?.credits||0),0);
  }
  const overCapReason=(termId,total)=>`${terms.find(t=>t.id===termId)?.label||termId} would have ${total} credits; a semester can have at most ${MAX_TERM_CREDITS}.`;
  /** Why a course can't fill an elective slot (empty list = it can). Any credit amount works within the 21-credit semester cap. */
  function electiveReasons(id,slotId){
    const c=catalog[id],slot=effectiveSlots().find(item=>item.id===slotId),term=terms.find(t=>t.id===slot?.term),reasons=[];
    if(!c||!slot||!term)return['Course or elective slot not found.'];
    const targetIndex=terms.indexOf(term);
    if(c.required)reasons.push('Reserved for a required course slot.');
    if(c.terms?.length&&!c.terms.includes(term.season))reasons.push(`Not offered in ${term.label} in the demo catalog.`);
    for(const prereq of c.prereqs||[]){if(!completed.has(prereq)&&!terms.slice(0,targetIndex).some(t=>(state.degree[t.id]||[]).includes(prereq)))reasons.push(`Take ${catalog[prereq]?.code||prereq} before ${term.label}.`)}
    if(completed.has(id)||terms.some(t=>(state.degree[t.id]||[]).includes(id))||slots.some(other=>other.id!==slotId&&state.electives[other.id]===id))reasons.push('Already included in your degree plan.');
    const total=termCredits(term.id)-(catalog[state.electives[slotId]]?.credits||0)+c.credits;
    if(total>MAX_TERM_CREDITS)reasons.push(overCapReason(term.id,total));
    return reasons;
  }
  function degreeAudit(degree=state.degree,electives=state.electives){
    const slots=effectiveSlots();
    const ids=terms.filter(t=>!t.past).flatMap(t=>degree[t.id]||[]),missing=requiredIds.filter(id=>!ids.includes(id));
    const issues=sequenceIssues(degree),bucket=state.bucket||[];
    missing.forEach(id=>issues.push(bucket.includes(id)?`${catalog[id].code} is in the staging area; place it in a semester.`:`${catalog[id].code} is missing from the degree plan.`));
    (state.minors||[]).forEach(pid=>programs[pid]?.courses.forEach(id=>{if(completed.has(id)||ids.includes(id)||Object.values(electives).includes(id)||requiredIds.includes(id))return;issues.push(bucket.includes(id)?`${catalog[id].code} (${programs[pid].name}) is in the staging area; place it in a semester.`:`${catalog[id].code} (${programs[pid].name}) is not in your plan.`)}));
    const looseStaged=bucket.filter(id=>!requiredIds.includes(id)&&!(state.minors||[]).some(pid=>programs[pid]?.courses.includes(id)));
    if(looseStaged.length)issues.push(`${looseStaged.length} staged ${looseStaged.length===1?'course is':'courses are'} not placed in a semester yet.`);
    const selected=[];
    slots.forEach((slot,index)=>{const id=electives[slot.id],c=catalog[id];if(!c){issues.push(`Choose a course for free elective ${index+1} (${terms.find(t=>t.id===slot.term)?.label||slot.term}).`);return}if(c.required)issues.push(`${c.code} is a required course and can't fill an elective slot.`);if(selected.includes(id)||ids.includes(id)||completed.has(id))issues.push(`${c.code} cannot count toward two requirements.`);selected.push(id);const termIndex=terms.findIndex(t=>t.id===slot.term);if(!c.terms.includes(terms[termIndex].season))issues.push(`${c.code} is not offered in this elective semester.`);c.prereqs.forEach(pre=>{if(!completed.has(pre)&&!terms.slice(0,termIndex).some(t=>(degree[t.id]||[]).includes(pre)))issues.push(`${c.code} needs ${catalog[pre]?.code||pre} first.`)})});
    terms.filter(t=>!t.past).forEach(t=>{const total=termCredits(t.id,degree,electives);if(total>MAX_TERM_CREDITS)issues.push(`${t.label} has ${total} credits; a semester can have at most ${MAX_TERM_CREDITS}.`)});
    const plannedCredits=[...new Set([...ids,...selected])].reduce((sum,id)=>sum+(catalog[id]?.credits||0),0),totalCredits=82+plannedCredits;
    if(totalCredits<120)issues.push(`${120-totalCredits} more credits are needed to reach the demo 120-credit requirement.`);
    const lastIndex=Math.max(...terms.map((t,i)=>(degree[t.id]?.length||slots.some(slot=>slot.term===t.id&&electives[slot.id]))?i:0));
    const finalTerm=terms[lastIndex];
    return{valid:issues.length===0,issues:[...new Set(issues)],plannedCredits,totalCredits,graduation:issues.length?'Plan incomplete':`${finalTerm.season==='spring'?'May':'December'} ${finalTerm.year}`,missing};
  }
  function moveDegree(id,from,to){
    const current=terms.find(t=>t.id===from),target=terms.find(t=>t.id===to);
    const elective=effectiveSlots().find(slot=>slot.term===from&&state.electives[slot.id]===id);
    if(!current||!target||(!state.degree[from]?.includes(id)&&!elective))return{ok:false,reasons:['Course or semester not found.']};
    if(current.past||target.past||completed.has(id))return{ok:false,reasons:['Completed semesters are locked.']};
    if(from===to)return{ok:true,reasons:[]};
    if(elective){
      const c=catalog[id],targetIndex=terms.indexOf(target),reasons=[];
      if(!c.terms.includes(target.season))reasons.push(`${c.code} is not offered in ${target.label}.`);
      c.prereqs.forEach(pre=>{if(!completed.has(pre)&&!terms.slice(0,targetIndex).some(term=>state.degree[term.id].includes(pre)))reasons.push(`${c.code} must stay after ${catalog[pre].code}.`)});
      if(termCredits(to)+c.credits>MAX_TERM_CREDITS)reasons.push(overCapReason(to,termCredits(to)+c.credits));
      if(reasons.length)return{ok:false,reasons};
      change(`Moved ${c.code} to ${target.label}`,()=>{state.preferences.degreeElectiveTerms={...state.preferences.degreeElectiveTerms,[elective.id]:to};state.review.status='draft'});return{ok:true,reasons:[]};
    }
    const candidate=clone(state.degree);candidate[from]=candidate[from].filter(value=>value!==id);candidate[to].push(id);
    const priorIssues=degreeAudit().issues,reasons=[...new Set([...sequenceIssues(candidate),...degreeAudit(candidate).issues.filter(issue=>!priorIssues.includes(issue))])];if(reasons.length)return{ok:false,reasons};
    change(`Moved ${catalog[id].code} to ${target.label}`,()=>{state.degree=candidate});return{ok:true,reasons:[]};
  }
  /** Where a course sits in the degree plan: a term id, 'bucket', or null. Elective-slot courses report null. */
  function locate(id){if(state.bucket.includes(id))return 'bucket';return terms.find(t=>(state.degree[t.id]||[]).includes(id))?.id||null}
  /**
   * Moves several courses at once (from terms or the staging bucket) to a future term or to the bucket.
   * Into a term: blocked if a course isn't offered that season or the move breaks prerequisite order.
   * Into the bucket: always allowed; any order problems it creates are returned as warnings.
   */
  function moveCourses(ids,to,label){
    const target=to==='bucket'?null:terms.find(t=>t.id===to);
    if(to!=='bucket'&&(!target||target.past))return{ok:false,reasons:['Choose a future semester.'],moved:[]};
    const movable=ids.filter(id=>catalog[id]&&!completed.has(id)&&locate(id)!==null&&!terms.find(t=>t.id===locate(id))?.past&&locate(id)!==to);
    if(!movable.length)return{ok:false,reasons:['Those courses are already there, completed, or held in an elective slot.'],moved:[]};
    const degree=clone(state.degree);let bucket=[...state.bucket];
    movable.forEach(id=>{const from=locate(id);if(from==='bucket')bucket=bucket.filter(x=>x!==id);else degree[from]=degree[from].filter(x=>x!==id)});
    if(target)degree[to]=[...degree[to],...movable];else bucket=[...bucket,...movable];
    const prior=sequenceIssues(state.degree),fresh=sequenceIssues(degree).filter(issue=>!prior.includes(issue));
    if(target){
      const reasons=[...movable.filter(id=>!catalog[id].terms.includes(target.season)).map(id=>`${catalog[id].code} is not offered in ${target.label}.`),...fresh];
      const total=termCredits(to,degree);
      if(total>MAX_TERM_CREDITS)reasons.push(overCapReason(to,total));
      if(reasons.length)return{ok:false,reasons:[...new Set(reasons)],moved:[]};
    }
    change(label||`Move ${movable.length===1?catalog[movable[0]].code:movable.length+' courses'} to ${target?target.label:'staging'}`,current=>{current.degree=degree;current.bucket=bucket;if(target)current.preferences.hiddenDegreeTerms=(current.preferences.hiddenDegreeTerms||[]).filter(id=>id!==to)});
    return{ok:true,reasons:[],warnings:target?[]:fresh,moved:movable};
  }
  function removeCourses(ids){
    const removable=ids.filter(id=>catalog[id]&&!completed.has(id)&&locate(id)!==null&&!terms.find(t=>t.id===locate(id))?.past);
    if(!removable.length)return[];
    change(`Remove ${removable.length===1?catalog[removable[0]].code:removable.length+' courses'}`,current=>{
      current.bucket=current.bucket.filter(id=>!removable.includes(id));
      terms.forEach(t=>{current.degree[t.id]=(current.degree[t.id]||[]).filter(id=>!removable.includes(id))});
    });
    return removable;
  }
  const inPlan=id=>completed.has(id)||locate(id)!==null||Object.values(state.electives).includes(id);
  /** Adds courses that aren't completed or already in the plan to the staging bucket. */
  function stageCourses(ids,label){
    const fresh=[...new Set(ids)].filter(id=>catalog[id]&&!inPlan(id));
    if(fresh.length)change(label||`Stage ${fresh.length===1?catalog[fresh[0]].code:fresh.length+' courses'}`,current=>{current.bucket=[...current.bucket,...fresh]});
    return fresh;
  }
  /** Declares a minor or concentration and stages its courses that aren't in the plan yet. */
  function addProgram(id){
    const program=programs[id];if(!program)return{added:false,staged:[]};
    const fresh=program.courses.filter(c=>catalog[c]&&!inPlan(c));
    const added=!state.minors.includes(id);
    if(added||fresh.length)change(`Add ${program.name}`,current=>{if(!current.minors.includes(id))current.minors=[...current.minors,id];current.bucket=[...current.bucket,...fresh.filter(c=>!current.bucket.includes(c))]});
    return{added,staged:fresh};
  }
  function saveSchedule(name){const entry={id:crypto.randomUUID?.()||String(Date.now()),name:(name||`Spring 2027 · Plan ${state.saved.length+1}`).slice(0,80),term:'spring-2027',courses:[...state.planner],preferences:clone(state.preferences),savedAt:new Date().toISOString()};change('Saved schedule',()=>state.saved.unshift(entry));S.showToast(storageAvailable?'Schedule saved':'Saved for this session',storageAvailable?'Find it in Saved schedules.':'Browser storage is unavailable. Keep this tab open.');return entry}
  const S=window.Syllo={catalog,terms,slots,completed,requiredIds,nextTerm:'spring-2027',historicalCredits:82,historicalTermCredits:{'fall-2024':16,'spring-2025':17,'fall-2025':16,'spring-2026':17,'fall-2026':16},state,eligible,recommendations:()=>requiredIds.filter(id=>eligible(id).ok),timeRange,days,conflicts,degreeAudit,sequenceIssues,moveDegree,change,undo,persist,emit,saveSchedule,escape,subscribe:fn=>{listeners.add(fn);return()=>listeners.delete(fn)},get canUndo(){return history.length>0},get undoLabel(){return history.at(-1)?.label||null},redo,get canRedo(){return future.length>0},get redoLabel(){return future.at(-1)?.label||null},programs,requirementGroups,maxTermCredits:MAX_TERM_CREDITS,termCredits,electiveReasons,locate,moveCourses,removeCourses,stageCourses,addProgram,get storageAvailable(){return storageAvailable},showToast:()=>{},openCourse:()=>{},navigate:()=>{}};
  Object.defineProperty(S,'slots',{get:effectiveSlots});
})();
