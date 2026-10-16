// Defines window.__bannerCollector.grid(): reads the Banner "Browse Classes" results grid in the CURRENT tab and
// returns a short summary; the sanitized JSON stays in window.__capture.
// Load this file once per page load, then call  await window.__bannerCollector.grid()  (page-script tool, top-level
// await) after a search has been run through the normal UI.
//
// Read-only: it only reads text from WHITELISTED cells. It never reads the instructor cell or the status cell
// (names and seat counts live there), and it makes no network request and clicks nothing.
// It refuses to return anything if a tripwire matches (seat text, an instructor marker, an e-mail address or a
// long digit run), so a layout change cannot leak personal data into a capture.
window.__bannerCollector = window.__bannerCollector || {};
window.__bannerCollector.grid = async () => {
	const clean = s => (s || '').replace(/\s+/g, ' ').trim();
	const main = document.querySelector('main') || document.body;
	const mainText = main.innerText;
	const cell = (row, property) => row.querySelector('td[data-property="' + property + '"]');
	const text = (row, property) => { const c = cell(row, property); return c ? clean(c.innerText) : null; };
	const LINK_LABEL = /\b(Lab|Lecture|Recitation|Discussion|Studio|Seminar|Independent Study)\s*-\s*Linked\b/;
	const rows = [...document.querySelectorAll('tbody tr')].filter(r => cell(r, 'courseReferenceNumber'));
	const grab = (re, source) => { const m = source.match(re); return m ? clean(m[1]) : null; };
	const result = {
		view: 'SEARCH_GRID',
		captured_at: new Date().toISOString(),
		header: {
			result_count: grab(/Search Results\s*(?:—|-)?\s*(\d+)\s*Classes/, mainText),
			term_label: grab(/Term:\s*(.+?)\s*Subject:/, mainText),
			subject_label: grab(/Subject:\s*(.+?)\s*Course Number:/, mainText),
			course_number: grab(/Course Number:\s*(\S+)/, mainText),
			records: grab(/Records:\s*(\d+)/, mainText)
		},
		rows: rows.map(r => ({
			crn: text(r, 'courseReferenceNumber'),
			subject_description: text(r, 'subjectDescription'),
			course_number: text(r, 'courseNumber'),
			section: text(r, 'sequenceNumber'),
			title: text(r, 'courseTitle'),
			hours: text(r, 'creditHours'),
			schedule_type: text(r, 'scheduleType'),
			link_label: ((text(r, 'attribute') || '').match(LINK_LABEL) || [null])[0],
			meetings: [...(cell(r, 'meetingTime') ? cell(r, 'meetingTime').querySelectorAll('div.meeting') : [])].map(m => clean(m.innerText))
		}))
	};
	const json = JSON.stringify(result);
	if (/seats?\s+remain|\(Primary\)|[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}|\d{8,}/i.test(json)) {
		return JSON.stringify({ error: 'SANITIZATION_TRIPWIRE', view: 'SEARCH_GRID' });
	}
	// The browser tool truncates long output, so the capture stays in page memory and is read in 900-character
	// chunks (window.__capture.json.slice(i * 900, (i + 1) * 900)). The SHA-256 lets the importer prove the
	// transcribed file is byte-for-byte what the page produced.
	const bytes = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(json));
	const sha256 = [...new Uint8Array(bytes)].map(b => b.toString(16).padStart(2, '0')).join('');
	window.__capture = { json, sha256 };
	return JSON.stringify({ ok: true, view: 'SEARCH_GRID', length: json.length, chunks: Math.ceil(json.length / 900), sha256 });
};
'grid extractor loaded'
