// Defines window.__bannerCollector.collectVisible(crns): runs the existing, read-only popup extractor (extract_popup.js, load it first in
// the same page) for each CRN you list that is VISIBLE in the current results grid, one after another, and downloads ONE JSON file with
// the sanitized captures. It adds no network request, clicks nothing itself and reads nothing the popup extractor does not (it only calls it).
//
//     await window.__bannerCollector.collectVisible(['12758', '12759'])    // the CRNs of the course you just searched
//
// Pacing: the popup extractor waits 6 s before and 3 s after each click (about 25 s per CRN). It stops at the first error and downloads what it
// has so far. The downloaded file (noncse-popups-<first crn>.json) is what data/catalog-research/import_noncse_details.py reads.
window.__bannerCollector = window.__bannerCollector || {};
window.__bannerCollector.collectVisible = async (crns) => {
	if (!window.__bannerCollector.popup) return 'Load extract_popup.js first.';
	if (!Array.isArray(crns) || !crns.length || !crns.every(c => /^\d{5}$/.test(c))) return 'Pass an array of 5-digit CRNs.';
	const visible = new Set([...document.querySelectorAll('td[data-property="courseReferenceNumber"]')].map(c => (c.innerText || '').trim()));
	const missing = crns.filter(c => !visible.has(c));
	if (missing.length) return 'Not visible in the results grid (search that course first): ' + missing.join(', ');
	const captures = [];
	let problem = null;
	for (const crn of crns) {
		const summary = JSON.parse(await window.__bannerCollector.popup(crn));
		if (summary.error) { problem = crn + ': ' + summary.error; break; }
		captures.push(JSON.parse(window.__capture.json));
	}
	if (captures.length) {
		const blob = new Blob([JSON.stringify(captures)], { type: 'application/json' });
		const a = document.createElement('a');
		a.href = URL.createObjectURL(blob);
		a.download = 'noncse-popups-' + captures[0].crn + '.json';
		document.body.appendChild(a); a.click(); a.remove();
	}
	return JSON.stringify({ captured: captures.length, of: crns.length, stoppedAt: problem });
};
'collectVisible loaded'
