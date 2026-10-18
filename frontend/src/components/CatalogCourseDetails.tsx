import type { CourseDetail, PredictedRating } from "@/lib/api/types";
import { RatingHistoryChart } from "./RatingHistoryChart";

function score(value: number | null) {
  return typeof value === "number" && Number.isFinite(value) ? `${value.toFixed(2)} / 5` : "No prediction available";
}

function confidence(value: string | null) {
  return value ? `${value[0]}${value.slice(1).toLowerCase()} confidence` : "Confidence unavailable";
}

export function Prediction({ rating, compact = false }: Readonly<{ rating: PredictedRating | null; compact?: boolean }>) {
  if (!rating || rating.basis !== "PREDICTED") return <span className="catalog-no-prediction">No prediction available</span>;
  return (
    <div className={`catalog-prediction${compact ? " is-compact" : ""}`}>
      <strong>Predicted quality: {score(rating.quality)}</strong>
      <span>{confidence(rating.confidence)}</span>
      {!compact && <>
        <span>Predicted difficulty: {score(rating.difficulty)} (5 is hardest)</span>
        <span>Coverage: {rating.sectionsPredicted} of {rating.sections} lecture sections</span>
        {rating.sectionsPredicted < rating.sections && <span>Partial coverage: sections without earlier ratings do not contribute to this estimate.</span>}
        <small>Estimated from earlier student opinions about instructors.</small>
      </>}
    </div>
  );
}

function RatingHistory({ course, term }: Readonly<{ course: CourseDetail; term?: string }>) {
  const history = course.ratingHistory ?? [];
  const match = term ? /^(\d{4})-([A-Z]+)$/.exec(term) : null;
  const year = match ? Number(match[1]) : new Date().getFullYear() + 1;
  const label = match ? `${match[2][0]}${match[2].slice(1).toLowerCase()} ${match[1]} (predicted)` : "Next term (predicted)";
  const prediction = (name: string) => {
    const ratings = course.sections.flatMap(s => s.instructorRatings ?? []).filter(r => r.name === name && (r.predictedQuality !== null || r.predictedDifficulty !== null));
    return ratings.length ? { quality: ratings[0].predictedQuality, difficulty: ratings[0].predictedDifficulty } : null;
  };
  const teaching = [...new Set(course.sections.flatMap(s => s.instructors))];
  const without = teaching.filter(name => !history.some(h => h.name === name));
  return <section className="detail-section rating-history">
    <h3>Professor rating history</h3>
    <p>Average student ratings per year, 1 to 5 (quality: 5 is best; difficulty: 5 is hardest). The dashed step is this term&apos;s prediction. Years with few ratings are hollow and say little on their own.</p>
    {!history.length && <p>No rating history is available for this course&apos;s instructors.</p>}
    {history.map(h => <RatingHistoryChart key={h.name} name={h.name} years={h.years} predicted={prediction(h.name)} predictedYear={year} predictedLabel={label} />)}
    {history.length > 0 && without.length > 0 && <p className="rating-history-missing">No rating history: {without.join(", ")}</p>}
  </section>;
}

export function CatalogCourseDetails({ course, term }: Readonly<{ course: CourseDetail; term?: string }>) {
  return <div className="catalog-course-details">
    <h2>{course.title}</h2>
    <p className="subtitle">{course.courseId} · Live catalog · {course.credits} credits</p>
    {course.description && <p className="course-description">{course.description}</p>}
    <section className="detail-section"><h3>Predicted instructor quality & difficulty</h3><Prediction rating={course.predictedRating} /></section>
    <RatingHistory course={course} term={term} />
    <section className="detail-section catalog-conditions">
      <h3>Conditions</h3>
      <p>Eligibility has not been verified. These are proposed options, not permission to register.</p>
      {course.conditions.length ? <ul>{course.conditions.map((condition, index) => <li key={`${condition.code}-${index}`}>
        <strong>{condition.message}</strong>
        <small>{condition.code.replaceAll("_", " ")} · Evidence: {condition.evidenceLevel.replaceAll("_", " ")} · {condition.outcome}</small>
      </li>)}</ul> : <p>No conditions listed. This does not confirm eligibility.</p>}
    </section>
    <section className="detail-section"><h3>Sections & instructor predictions</h3>
      {!course.sections.length && <p>No sections listed for this term.</p>}
      {course.sections.map(section => {
        const ratings = section.instructorRatings ?? [];
        const unratedNames = section.instructors.filter(name => !ratings.some(rating => rating.name === name));
        return <section className="catalog-section" key={section.crn}>
          <h4>Section {section.sectionCode} · {section.componentType}</h4>
          <p>CRN {section.crn} · {section.credits} credits · Availability: {section.status}</p>
          {section.lectureLink && <p>Linked lecture CRN: {section.lectureLink}</p>}
          {section.meetings.length ? <ul>{section.meetings.map((meeting, index) => <li key={index}>
            {meeting.days.join(" / ")} · {meeting.start}–{meeting.end}{meeting.location ? ` · ${meeting.location}` : ""}
          </li>)}</ul> : <p>Meeting time unavailable or arranged.</p>}
          {ratings.map((rating, index) => <div className="catalog-instructor" key={`${rating.name}-${index}`}>
            <strong>{rating.name}</strong>
            <span>Predicted quality: {score(rating.predictedQuality)}</span>
            <span>Predicted difficulty: {score(rating.predictedDifficulty)}</span>
            <span>{confidence(rating.confidence)}</span>
            {rating.ratingCount !== null && <small>Based on {rating.ratingCount} earlier ratings · {rating.courseSpecific ? "For this course" : "Instructor history across courses"}</small>}
          </div>)}
          {unratedNames.map(name => <div className="catalog-instructor" key={name}><strong>{name}</strong><span>No prediction available</span></div>)}
          {!ratings.length && !unratedNames.length && <p>Instructor information unavailable.</p>}
        </section>;
      })}
    </section>
  </div>;
}
