/* Degree chat UI, backed by the local demo plan until the AI service is connected. */
(() => {
  'use strict';
  const S=()=>window.Syllo;
  const safe=text=>S().escape(text);
  const messages=()=>Array.isArray(S().state.degreeChat)?S().state.degreeChat:[];
  function markup(){
    return `<section class="degree-ai-card" aria-label="Syllo AI degree assistant"><div class="degree-ai-heading"><span aria-hidden="true">✦</span><div><h2>Syllo AI</h2><p>Your degree, one question at a time.</p></div></div><div id="degree-ai-messages" class="degree-ai-messages" role="log" aria-live="polite">${messages().map(message=>`<article class="degree-ai-message ${message.role==='user'?'from-you':'from-syllo'}"><strong>${message.role==='user'?'You':'Syllo'}</strong><p>${safe(message.text)}</p></article>`).join('')}</div><div class="degree-ai-starters" aria-label="Suggested questions">${['What’s left to graduate?','Explain my prerequisites','Suggest electives'].map(question=>`<button type="button" data-degree-ai-question="${safe(question)}">${safe(question)}</button>`).join('')}</div><form id="degree-ai-form"><label for="degree-ai-input">What would you like to know?</label><div class="degree-ai-composer"><textarea id="degree-ai-input" rows="2" maxlength="4000" placeholder="Ask about your courses, degree plan, or next steps…" required>${safe(S().state.degreeChatDraft||'')}</textarea><button class="button primary" type="submit">Send <span aria-hidden="true">↑</span></button></div></form><small class="degree-ai-note">Demo assistant · Answers use your saved plan. Enter to send, Shift+Enter for a new line.</small></section>`;
  }
  function answer(question){
    const api=S(),text=question.toLowerCase(),audit=api.degreeAudit();
    const matched=Object.entries(api.catalog).filter(([,course])=>text.replace(/\s/g,'').includes(course.code.toLowerCase().replace(/\s/g,''))||text.includes(course.name.toLowerCase()));
    if(matched.length)return matched.slice(0,3).map(([id,c])=>{
      const placed=api.terms.find(term=>api.state.degree[term.id]?.includes(id))||api.terms.find(term=>api.slots.some(slot=>slot.term===term.id&&api.state.electives[slot.id]===id));
      const prerequisite=c.prereqs.length?c.prereqs.map(pre=>`${api.catalog[pre].code}${api.completed.has(pre)?' (completed)':''}`).join(', '):'None in the demo catalog';
      return `${c.code} · ${c.name}\n${c.credits} credits · ${c.requirement}\nPrerequisites: ${prerequisite}.\n${api.completed.has(id)?'Completed in your record.':placed?`Planned for ${placed.label}.`:'Not currently in your plan.'}\nDemo offering: ${c.terms.join(' and ')}.${/move|drag|earlier|later/.test(text)?' Move it to a future semester using the drag handle or Move to control; prerequisite order and term availability are checked.':''}`;
    }).join('\n\n');
    if(/elective|interest/.test(text)){
      const choices=Object.entries(api.catalog).filter(([,c])=>!c.required);
      return `Your plan has ${api.slots.length} free-elective slots. Here are options in the demo catalog:\n\n${choices.map(([,c])=>`${c.code} · ${c.name} — ${c.credits} credits, ${c.interest}${c.prereqs.length?`; take ${c.prereqs.map(id=>api.catalog[id].code).join(', ')} first`:''}`).join('\n')}\n\nClick an elective slot below to compare courses and choose one. Each slot checks credits and prerequisites.`;
    }
    if(/prereq|sequence|order|before|after/.test(text)){
      const pairs=api.requiredIds.filter(id=>api.catalog[id].prereqs.some(pre=>!api.completed.has(pre)));
      return `Courses with future prerequisites in your plan:\n\n${pairs.map(id=>`${api.catalog[id].code} comes after ${api.catalog[id].prereqs.map(pre=>api.catalog[pre].code).join(', ')}.`).join('\n')}\n\nThese must be in different semesters, with the prerequisite first. Completed semesters stay locked, and invalid moves are blocked.`;
    }
    if(/advisor|review|approv|submit/.test(text))return audit.valid?'Your demo graduation plan is ready to share. Use “Send to advisor” below. Advisor comments and decisions will appear in the Advisor portal, with an alert for new feedback.':`Before submitting to your advisor, finish these items:\n\n${audit.issues.map(issue=>`• ${issue}`).join('\n')}\n\nYour advisor demo can still open the plan at any time.`;
    if(/graduat|progress|left|remain|credit|plan|degree|next/.test(text))return `You have ${api.historicalCredits} completed credits and ${audit.plannedCredits} planned credits, covering ${audit.totalCredits} of the demo 120-credit requirement.\n\n${audit.valid?`Your current plan reaches graduation in ${audit.graduation}.`:`Next steps:\n${audit.issues.map(issue=>`• ${issue}`).join('\n')}`}\n\nYou can arrange future courses and choose electives in the plan below.`;
    if(/^(hi|hello|hey|thanks|thank you)[! .?]*$/.test(text))return 'Hi! Ask me about your courses, prerequisites, elective options, or what you still need for graduation.';
    return 'I can help with questions about the degree plan shown here. Try a course code, “What’s left to graduate?”, or “Which electives can I take?”\n\nThis demo uses your saved course and degree data. A connected AI service is needed for broader questions.';
  }
  function send(value){
    const question=value.trim();if(!question)return;
    S().state.degreeChat=[...messages(),{role:'user',text:question.slice(0,4000)},{role:'assistant',text:answer(question)}].slice(-60);
    S().state.degreeChatDraft='';S().persist();
    const card=document.querySelector('.degree-ai-card');if(card)card.outerHTML=markup();
    const log=document.getElementById('degree-ai-messages');if(log)log.scrollTop=log.scrollHeight;
    document.getElementById('degree-ai-input')?.focus({preventScroll:true});
  }
  document.addEventListener('submit',event=>{if(event.target.id==='degree-ai-form'){event.preventDefault();send(document.getElementById('degree-ai-input').value)}});
  document.addEventListener('click',event=>{const button=event.target.closest('[data-degree-ai-question]');if(button)send(button.dataset.degreeAiQuestion)});
  document.addEventListener('input',event=>{if(event.target.id==='degree-ai-input'){S().state.degreeChatDraft=event.target.value;S().persist()}});
  document.addEventListener('keydown',event=>{if(event.target.id==='degree-ai-input'&&event.key==='Enter'&&!event.shiftKey&&!event.isComposing){event.preventDefault();event.target.form.requestSubmit()}});
  window.SylloDegreeAI={markup,answer};
})();
