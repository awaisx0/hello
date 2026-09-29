const UNITS = ['days', 'weeks', 'months'];

// Pure calendar-day math on 'YYYY-MM-DD' strings, using local time components
// only (never toISOString) so results never shift a day due to UTC conversion.
function addInterval(dateStr, amount, unit) {
  if (!UNITS.includes(unit)) throw new Error(`Unknown recurrence unit: ${unit}`);
  const [y, m, d] = dateStr.split('-').map(Number);
  const date = new Date(y, m - 1, d);

  if (unit === 'days') date.setDate(date.getDate() + amount);
  else if (unit === 'weeks') date.setDate(date.getDate() + amount * 7);
  else if (unit === 'months') date.setMonth(date.getMonth() + amount);

  const yy = date.getFullYear();
  const mm = String(date.getMonth() + 1).padStart(2, '0');
  const dd = String(date.getDate()).padStart(2, '0');
  return `${yy}-${mm}-${dd}`;
}

module.exports = { addInterval, UNITS };
