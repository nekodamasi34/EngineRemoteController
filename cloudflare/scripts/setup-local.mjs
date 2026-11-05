import { randomBytes } from 'node:crypto';
import { existsSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
const target = fileURLToPath(new URL('../.dev.vars', import.meta.url));
if (existsSync(target)) {
  console.log('.dev.vars は作成済みです。既存のキーを保持しました。');
} else {
  writeFileSync(target, `OPERATOR_TOKEN=${randomBytes(32).toString('hex')}\nDEVICE_TOKEN=${randomBytes(32).toString('hex')}\n`, {mode:0o600, flag:'wx'});
  console.log('.dev.vars に操作キーと端末キーを作成しました。値は画面へ出力していません。');
}
