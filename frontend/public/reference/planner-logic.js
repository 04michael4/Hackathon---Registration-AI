/* Pure planner rules shared by the browser runtime and the Vitest suite.
 * No DOM, no storage, no Syllo state: every function returns new values and never mutates its inputs.
 * Loaded as a classic script (window.SylloPlannerLogic) and as CommonJS in tests.
 */
(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.SylloPlannerLogic = api;
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  /* ---------- Forgiving course-code search ---------- */

  /** "CSE 017", "cse017", "CSE 17" → "cse17". Returns null when the text isn't shaped like a course code. */
  function codeKey(value) {
    const compact = String(value ?? '').toLowerCase().replace(/\s+/g, '');
    const match = compact.match(/^([a-z]+)(\d+)([a-z]?)$/);
    if (!match) return null;
    return match[1] + match[2].replace(/^0+(?=\d)/, '') + match[3];
  }

  /**
   * 3 = the query is this course's code, 2 = the start of its code, 1 = every word appears in its text, 0 = no match.
   * An empty query matches everything (1), as before.
   */
  function searchScore(course, query) {
    const q = String(query ?? '').trim().toLowerCase();
    if (!q) return 1;
    const queryKey = codeKey(q), courseKey = codeKey(course.code);
    if (queryKey && courseKey) {
      if (queryKey === courseKey) return 3;
      if (courseKey.startsWith(queryKey)) return 2;
    }
    const text = [course.code, course.name, course.description, course.requirement, course.section, course.interest].join(' ').toLowerCase();
    return q.split(/\s+/).every(token => text.includes(token)) ? 1 : 0;
  }

  /* ---------- Merged course list: R = recommended, P = planned ---------- */

  const CLASS_TYPES = {
    planned: { letter: 'P', label: 'Planned class' },
    recommended: { letter: 'R', label: 'Recommended class' },
  };

  /**
   * One list from the two existing sources, without reclassifying anything:
   * a course on the schedule is Planned; a recommendation not on the schedule is Recommended.
   */
  function courseList(plannerIds, recommendedIds) {
    const planned = [...new Set(plannerIds)];
    const recommended = new Set(recommendedIds);
    return [
      ...planned.map(id => ({ id, type: 'planned', alsoRecommended: recommended.has(id) })),
      ...[...recommended].filter(id => !planned.includes(id)).map(id => ({ id, type: 'recommended', alsoRecommended: true })),
    ];
  }

  /* ---------- Professor rating (demo Rate My Professor data) ---------- */

  const RATING_TIERS = [
    { tier: 'excellent', min: 4.5, label: 'Excellent', range: '4.5–5.0' },
    { tier: 'good', min: 4.0, label: 'Good', range: '4.0–4.4' },
    { tier: 'mixed', min: 3.0, label: 'Mixed', range: '3.0–3.9' },
    { tier: 'low', min: 0, label: 'Low', range: 'Below 3.0' },
  ];
  const NO_RATING = { tier: 'none', label: 'No rating', range: 'No data' };

  function ratingTier(rating) {
    const value = Number(rating);
    if (rating === null || rating === undefined || rating === '' || !Number.isFinite(value)) return NO_RATING;
    return RATING_TIERS.find(t => value >= t.min) || RATING_TIERS.at(-1);
  }

  /** "3.7 / 5" → 3.7; anything else → null. */
  function scoreOf(value) {
    const match = String(value ?? '').match(/^\s*(\d+(?:\.\d+)?)\s*(?:\/\s*5)?\s*$/);
    return match ? Number(match[1]) : null;
  }

  /** Professor name and scores for a course's scheduled section (or an alternative section). */
  function professorRating(source) {
    const name = source.instructor || String(source.section || '').split('·').slice(1).join('·').trim() || null;
    const quality = scoreOf(source.rating), difficulty = scoreOf(source.difficulty);
    return { name, quality, difficulty, ...ratingTier(quality) };
  }

  /* ---------- Other sections of a scheduled course ---------- */

  const DAY_NAMES = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

  function parseDays(text) {
    return String(text ?? '').split('/').map(d => d.trim().slice(0, 3)).filter(d => DAY_NAMES.includes(d));
  }
  function minutesOf(text, period) {
    const match = String(text).trim().match(/^(\d{1,2}):(\d{2})\s*(AM|PM)?$/i);
    if (!match) return null;
    return (Number(match[1]) % 12 + ((match[3] || period || 'AM').toUpperCase() === 'PM' ? 12 : 0)) * 60 + Number(match[2]);
  }
  /** "1:10–2:25 PM" → [790, 865]. Same reading as the demo catalog's own parser. */
  function parseTimeRange(text) {
    const parts = String(text ?? '').split('–');
    if (parts.length !== 2) return null;
    const period = parts[1].match(/AM|PM/i)?.[0];
    const start = minutesOf(parts[0], period), end = minutesOf(parts[1], period);
    return start === null || end === null ? null : [start, end];
  }

  /** "010 · Prof. Maya Chen" → "010". */
  function sectionNumber(course) {
    return String(course.section ?? '').split('·')[0].trim();
  }

  /**
   * The other offered sections of a course in the planned term: never the scheduled one.
   * Each section lists every meeting (lecture, lab, recitation…) that comes with it.
   */
  function alternativeSections(course) {
    const current = sectionNumber(course);
    return (course.alternatives || [])
      .filter(alt => alt.section !== current)
      .map(alt => ({
        section: alt.section,
        instructor: alt.instructor || null,
        status: alt.status || null,
        rating: alt.rating ?? null,
        difficulty: alt.difficulty ?? null,
        meetings: (alt.meetings || []).map(m => ({ component: m.component || 'Lecture', days: m.days, time: m.time })),
      }));
  }

  /** Calendar blocks (one per meeting day) for the alternative sections; untimed meetings are skipped. */
  function previewBlocks(course, sections, weekdays) {
    return sections.flatMap(alt => alt.meetings.flatMap((meeting, index) => {
      const range = parseTimeRange(meeting.time);
      if (!range) return [];
      return parseDays(meeting.days).filter(day => weekdays.includes(day)).map(day => ({
        key: `alt|${course.code}|${alt.section}|${index}|${day}`,
        section: alt.section, component: meeting.component, day, start: range[0], end: range[1],
        days: meeting.days, time: meeting.time, instructor: alt.instructor, status: alt.status,
      }));
    }));
  }

  /** Side-by-side lanes for blocks that overlap on the same day: { key: { lane, lanes } }. Deterministic. */
  function assignLanes(blocks) {
    const out = {};
    const byDay = {};
    blocks.forEach(b => (byDay[b.day] = byDay[b.day] || []).push(b));
    Object.values(byDay).forEach(items => {
      items.sort((a, b) => a.start - b.start || a.end - b.end || a.key.localeCompare(b.key));
      let cluster = [], clusterEnd = -1;
      const flush = () => { const lanes = Math.max(1, ...cluster.map(c => c.lane + 1)); cluster.forEach(c => (out[c.key] = { lane: c.lane, lanes })); cluster = []; };
      items.forEach(block => {
        if (block.start >= clusterEnd) { flush(); clusterEnd = -1; }
        const used = new Set(cluster.filter(c => c.end > block.start).map(c => c.lane));
        let lane = 0; while (used.has(lane)) lane++;
        cluster.push({ key: block.key, end: block.end, lane });
        clusterEnd = Math.max(clusterEnd, block.end);
      });
      flush();
    });
    return out;
  }

  /**
   * Section-preview state: null, or { courseId }. Showing a preview never touches the schedule.
   * toggle: same course hides it, another course replaces it. clear: hides it.
   * sync: hides it when the course left the schedule or the planner isn't on screen.
   */
  function nextSectionPreview(current, action) {
    switch (action.type) {
      case 'toggle': return current && current.courseId === action.courseId ? null : { courseId: action.courseId };
      case 'clear': return null;
      case 'sync': return current && action.active && action.plannerIds.includes(current.courseId) ? current : null;
      default: return current;
    }
  }

  /* ---------- Degree planner: categories, trackers, unified search, selection ---------- */

  /** Lehigh color category for a course card: major, minor (or concentration), gened, or elective. */
  const CATEGORIES = {
    major: { label: 'Major', name: 'Major requirement' },
    minor: { label: 'Minor', name: 'Minor or concentration' },
    gened: { label: 'GenEd', name: 'General education requirement' },
    elective: { label: 'Elective', name: 'Free or humanities elective' },
  };
  function courseCategory(id, course, programCourseIds) {
    if ((programCourseIds || []).includes(id) || /minor|concentration/i.test(course.requirement || '')) return 'minor';
    if (course.required && /general education|humanit/i.test(course.requirement || '')) return 'gened';
    if (course.required) return 'major';
    return 'elective';
  }

  /** Credits completed, planned in a semester, and staged (in the bucket) for a set of courses. */
  function creditProgress(courseIds, catalog, where) {
    const sum = ids => ids.reduce((total, id) => total + (catalog[id]?.credits || 0), 0);
    const ids = courseIds.filter(id => catalog[id]);
    const completed = ids.filter(id => where(id) === 'completed');
    const planned = ids.filter(id => where(id) === 'planned');
    const staged = ids.filter(id => where(id) === 'staged');
    return { completedCredits: sum(completed), plannedCredits: sum(planned), stagedCredits: sum(staged), totalCredits: sum(ids) };
  }

  const INTEREST_ALIASES = { finance: ['economics', 'economic'], security: ['systems', 'network'], machine: ['data', 'statistic'], learning: ['data', 'statistic'] };
  function interestScore(course, query) {
    const words = String(query ?? '').toLowerCase().trim().split(/\s+/).filter(Boolean);
    const text = [course.name, course.description, course.interest].join(' ').toLowerCase();
    return words.reduce((n, w) => n + (text.includes(w) ? 3 : (INTEREST_ALIASES[w] || []).some(a => text.includes(a)) ? 1 : 0), 0);
  }

  /** One semester search: direct course matches first, then related interests with electives first. */
  function semesterSearch(catalog, query, { electivesOnly = false, isEligible = () => true } = {}) {
    return Object.entries(catalog)
      .map(([id, course]) => ({ id, course, direct: searchScore(course, query), interest: interestScore(course, query) }))
      .filter(match => (match.direct > 0 || match.interest > 0) && !(electivesOnly && match.course.required))
      .sort((a, b) => b.direct - a.direct
        || (!a.direct && !b.direct ? Number(a.course.required) - Number(b.course.required) : 0)
        || b.interest - a.interest
        || Number(isEligible(b.id)) - Number(isEligible(a.id))
        || a.course.code.localeCompare(b.course.code))
      .map(match => match.id);
  }

  /**
   * One search for the degree planner. Tags narrow it: 'code' matches course codes only, 'interest' matches
   * interests and descriptions; 'major', 'minor', 'gened' restrict to those categories (several = any of them).
   * Minors and concentrations appear as programs when no category tag or the Minor tag is on.
   */
  function degreeSearch(catalog, programs, query, tags, programCourseIds) {
    const q = String(query ?? '').trim();
    const on = new Set(tags || []);
    const categories = ['major', 'minor', 'gened'].filter(t => on.has(t));
    if (!q && !on.size) return { programs: [], courses: [] };
    const score = (id, c) => {
      if (!q) return 1;
      const code = () => { const key = codeKey(q), own = codeKey(c.code); return key && own ? (key === own ? 3 : own.startsWith(key) ? 2 : 0) : c.code.toLowerCase().replace(/\s+/g, '').includes(q.toLowerCase().replace(/\s+/g, '')) ? 2 : 0; };
      if (on.has('code') && on.has('interest')) return Math.max(code(), interestScore(c, q) ? 1 : 0);
      if (on.has('code')) return code();
      if (on.has('interest')) return interestScore(c, q);
      return searchScore(c, q);
    };
    const courses = Object.entries(catalog)
      .filter(([id, c]) => !categories.length || categories.includes(courseCategory(id, c, programCourseIds)))
      .map(([id, c]) => [id, score(id, c)])
      .filter(([, n]) => n > 0)
      .sort((a, b) => b[1] - a[1] || catalog[a[0]].code.localeCompare(catalog[b[0]].code))
      .map(([id]) => id);
    const showPrograms = (!categories.length || on.has('minor')) && !(on.has('code') && !on.has('interest'));
    const tokens = q.toLowerCase().split(/\s+/).filter(Boolean);
    const programIds = showPrograms ? Object.entries(programs || {})
      .filter(([, p]) => tokens.length ? tokens.every(t => `${p.name} ${p.kind}`.toLowerCase().includes(t)) : on.has('minor'))
      .map(([id]) => id) : [];
    return { programs: programIds, courses };
  }

  /** Marquee selection: true when two client rectangles overlap. */
  function rectsIntersect(a, b) {
    return a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom;
  }
  /** Normalized rectangle from two pointer positions. */
  function rectFromPoints(x1, y1, x2, y2) {
    return { left: Math.min(x1, x2), top: Math.min(y1, y2), right: Math.max(x1, x2), bottom: Math.max(y1, y2) };
  }

  return {
    CATEGORIES, courseCategory, creditProgress, interestScore, degreeSearch, rectsIntersect, rectFromPoints,
    codeKey, searchScore, semesterSearch,
    CLASS_TYPES, courseList,
    RATING_TIERS, NO_RATING, ratingTier, scoreOf, professorRating,
    parseDays, parseTimeRange, sectionNumber, alternativeSections, previewBlocks, assignLanes, nextSectionPreview,
  };
});
