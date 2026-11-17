/**
 * Ratings based on fewer votes than this aren't shown: a new film's 9.0 from two dozen early fans says
 * little, and looks inflated next to a 7.5 from thousands.
 */
export const MIN_VOTES = 50;

/** 1234 -> "1.2k", 25000 -> "25k". */
export function formatVotes(n: number): string {
  if (n < 1000) return String(n);
  const k = n / 1000;
  return `${k < 10 ? k.toFixed(1).replace(/\.0$/, '') : Math.round(k)}k`;
}

interface Props {
  rating: number | null;
  voteCount: number | null;
  /** Show the vote count next to the score. */
  withVotes?: boolean;
  className?: string;
}

/** TMDB users' average score out of 10, labelled with its source. Nothing for unrated movies. */
export function Rating({ rating, voteCount, withVotes, className = '' }: Props) {
  if (rating == null || voteCount == null || voteCount < MIN_VOTES) return null;
  return (
    <span className={`inline-flex items-center gap-1 font-bold text-sun-300 ${className}`}
          title={`${rating.toFixed(1)}/10 from ${voteCount.toLocaleString('en-IN')} votes on TMDB`}>
      <span aria-hidden="true">★</span>
      <span className="sr-only">Rated</span>
      {rating.toFixed(1)}
      <span className="font-semibold text-ink-400">/10{withVotes ? ` · ${formatVotes(voteCount)} votes` : ''} · TMDB</span>
    </span>
  );
}
