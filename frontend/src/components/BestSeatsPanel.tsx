import { useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { ApiError } from '../api/client';
import { catalogApi, checkoutApi } from '../api/endpoints';
import type { PriceView, SeatCategory, SeatSuggestion } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { CATEGORY_LABEL, formatRupees, pluralize } from '../lib/format';
import { Spinner } from './ui';

const MAX_SEATS = 10;

interface Props {
  showId: number;
  prices: PriceView[];
  /** Seat ids currently selected on the map, to mark which suggestion is showing. */
  selected: Set<number>;
  /** Show a suggestion on the seat map (it becomes the selection). */
  onPick: (suggestion: SeatSuggestion) => void;
  /** True while "hold the best seats" is in flight: those seats turning held is us, not someone else. */
  onHolding: (holding: boolean) => void;
}

function isShowing(suggestion: SeatSuggestion, selected: Set<number>) {
  return suggestion.seatIds.length === selected.size && suggestion.seatIds.every((id) => selected.has(id));
}

/** "Pick for me": the server scores every free block of seats and can hold the best one in one click. */
export function BestSeatsPanel({ showId, prices, selected, onPick, onHolding }: Props) {
  const { session } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [count, setCount] = useState(2);
  const [category, setCategory] = useState<SeatCategory | undefined>();
  const [busy, setBusy] = useState<'find' | 'hold' | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [suggestions, setSuggestions] = useState<SeatSuggestion[] | null>(null);
  const isAdmin = session?.role === 'ROLE_ADMIN';

  const change = (next: { count?: number; category?: SeatCategory }) => {
    if ('count' in next) setCount(next.count!);
    if ('category' in next) setCategory(next.category);
    setSuggestions(null); // they were for a different request
    setError(null);
  };

  const noneLeft = `No row has ${pluralize(count, 'free seat')}${count > 1 ? ' together' : ''}`
    + `${category ? ` in ${CATEGORY_LABEL[category]}` : ''} right now. Try fewer seats${category ? ' or any category' : ''}.`;

  const find = async () => {
    setBusy('find');
    setError(null);
    try {
      const best = await catalogApi.bestSeats(showId, count, category);
      setSuggestions(best.suggestions);
      if (best.suggestions.length > 0) onPick(best.suggestions[0]);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not look for seats.');
    } finally {
      setBusy(null);
    }
  };

  const holdBest = async () => {
    if (!session) {
      navigate('/login', { state: { from: location.pathname } });
      return;
    }
    setBusy('hold');
    setError(null);
    onHolding(true);
    try {
      const hold = await checkoutApi.holdBest(showId, count, category);
      navigate(`/checkout/${hold.holdId}`);
    } catch (e) {
      onHolding(false);
      setBusy(null);
      setError(e instanceof ApiError ? e.message : 'Could not hold seats.');
    }
  };

  const chip = (active: boolean) =>
    `rounded-full px-3 py-1.5 text-xs font-bold transition ${active ? 'bg-marquee text-ink-950' : 'bg-ink-900 text-ink-300 hover:bg-white/5'}`;

  return (
    <section className="card relative overflow-hidden p-5 sm:p-6" aria-labelledby="best-seats-title">
      <div className="absolute -right-10 -top-10 h-40 w-40 rounded-full bg-mint-400/20 blur-3xl" aria-hidden="true" />
      <h2 id="best-seats-title" className="text-lg font-extrabold text-white">Not sure where to sit? ✨</h2>
      <p className="mt-1 max-w-2xl text-sm text-ink-300">
        We score every group of free seats on how central it is and how far it is from the screen, and keep you all
        together. See the best ones on the map, or have them held for you straight away.
      </p>

      <div className="mt-5 flex flex-wrap items-center gap-x-6 gap-y-3">
        <div className="flex items-center gap-1 rounded-full bg-ink-900 p-1" role="group" aria-label="How many seats">
          <button type="button" className="h-9 w-9 rounded-full text-lg font-bold text-ink-300 transition hover:bg-white/5 disabled:opacity-40"
                  aria-label="Fewer seats" disabled={count <= 1} onClick={() => change({ count: count - 1 })}>−</button>
          <output className="min-w-[4.5rem] text-center text-sm font-bold text-white" aria-live="polite">
            {pluralize(count, 'seat')}
          </output>
          <button type="button" className="h-9 w-9 rounded-full text-lg font-bold text-ink-300 transition hover:bg-white/5 disabled:opacity-40"
                  aria-label="More seats" disabled={count >= MAX_SEATS} onClick={() => change({ count: count + 1 })}>+</button>
        </div>
        <div className="flex flex-wrap items-center gap-1.5" role="group" aria-label="Seat category">
          <button type="button" className={chip(!category)} aria-pressed={!category}
                  onClick={() => change({ category: undefined })}>Any</button>
          {prices.map((p) => (
            <button key={p.category} type="button" className={chip(category === p.category)}
                    aria-pressed={category === p.category} onClick={() => change({ category: p.category })}>
              {CATEGORY_LABEL[p.category]} · {formatRupees(p.pricePaise)}
            </button>
          ))}
        </div>
      </div>

      <div className="mt-4 flex flex-wrap items-center gap-3">
        <button type="button" className="btn-secondary" onClick={find} disabled={busy !== null}>
          {busy === 'find' && <Spinner />} Show best seats on the map
        </button>
        <button type="button" className="btn-primary" onClick={holdBest} disabled={busy !== null || isAdmin}>
          {busy === 'hold' && <Spinner />} {session ? 'Hold the best seats for me' : 'Sign in to hold the best seats'}
        </button>
      </div>

      {error && <p className="mt-3 text-sm text-flame-300" role="alert">{error}</p>}
      {suggestions?.length === 0 && <p className="mt-3 text-sm text-sun-300" role="status">{noneLeft}</p>}
      {suggestions && suggestions.length > 0 && (
        <ul className="mt-4 grid gap-2 sm:grid-cols-3" aria-label="Best seats, best first">
          {suggestions.map((s, i) => {
            const showing = isShowing(s, selected);
            return (
              <li key={s.seatIds.join('-')}>
                <button type="button" aria-pressed={showing} onClick={() => onPick(s)}
                        className={`h-full w-full rounded-xl border p-3 text-left transition ${showing ? 'border-sun-400/70 bg-sun-400/10' : 'border-white/10 bg-ink-900 hover:border-white/20'}`}>
                  <span className="flex items-baseline justify-between gap-2">
                    <span className="text-sm font-extrabold text-white">
                      {i === 0 ? 'Best: ' : ''}{s.labels.length > 1 ? `${s.labels[0]}–${s.labels[s.labels.length - 1]}` : s.labels[0]}
                    </span>
                    <span className="text-xs font-bold text-mint-300" title="Score out of 100">{s.score}/100</span>
                  </span>
                  <span className="mt-0.5 block text-xs text-ink-300">
                    {CATEGORY_LABEL[s.category]} · {formatRupees(s.ticketsPaise)}
                  </span>
                  <span className="mt-1.5 block text-xs text-ink-400">{s.reasons.join(' · ')}</span>
                </button>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}
