export const PALETTE = ['#177d70','#5e66b7','#d88055','#a58c36','#4b8eac','#a26c8f','#697681'];
const months = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];

// Parse statement dates explicitly: Safari/Chromium need not agree on non-ISO Date.parse().
export function ledgerStamp(text) {
  const iso = /^(\d{4})-(\d{2})-(\d{2})/.exec(text);
  if (iso) return Date.UTC(+iso[1], +iso[2]-1, +iso[3]);
  const m = /(\d{1,2}) (\w{3}) (\d{4})(?:,\s*(\d{1,2}):(\d{2})(?::(\d{2}))?\s*(AM|PM))?/i.exec(text);
  if (!m || !months.includes(m[2])) return NaN;
  const hour = m[4] ? +m[4] % 12 + (m[7].toUpperCase() === 'PM' ? 12 : 0) : 0;
  return Date.UTC(+m[3], months.indexOf(m[2]), +m[1], hour, +(m[5] || 0), +(m[6] || 0));
}
export function monthKey(text) {
  const n = ledgerStamp(text);
  return Number.isFinite(n) ? new Date(n).toISOString().slice(0,7) : '';
}
export function monthLabel(key) {
  const [year, month] = key.split('-'); return `${months[Number(month)-1]} ${year}`;
}
export const transfer = t => ['Investment / FD Sweep','Card repayment'].includes(t.category);
export const paise = n => Math.round(Number(n) * 100);
export const sum = (rows, field='amount') => rows.reduce((a, r) => a + paise(r[field]), 0) / 100;
export const accounts = data => data.bankAccounts.map(b => ({value:b.id, label:`${b.bankName} ${b.maskedNumber}`}));
export const sourceLabel = t => ({ FINVU_AA:'Sample bank feed', GMAIL_ALERT:'Sample email', AI_PASTE:'Pasted alert', STATEMENT_CSV:'Statement CSV', SIMULATION:'Simulation' }[t.source] || 'Ledger');
