export function estAdresseIP(hote: string): boolean {
  if (/^[\d.]+(?::\d+)?$/.test(hote)) return true;
  if (/^\[[0-9a-fA-F:]+\](?::\d+)?$/.test(hote)) return true;
  if (/^[0-9a-fA-F]*:[0-9a-fA-F:]*$/.test(hote)) return true;
  return false;
}
