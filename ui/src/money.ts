/** Minor units (cents) to "-1,234.05" with integer math only. */
export function formatMoney(cents: number): string {
  const sign = cents < 0 ? "-" : "";
  const abs = Math.abs(cents);
  const dollars = Math.trunc(abs / 100);
  const rest = abs - dollars * 100;
  const grouped = String(dollars).replace(/\B(?=(\d{3})+(?!\d))/g, ",");
  return `${sign}${grouped}.${rest < 10 ? "0" : ""}${rest}`;
}
