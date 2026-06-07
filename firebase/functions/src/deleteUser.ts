/**
 * Delete a user's Firebase Authentication account and associated Firestore data
 * in response to an account-deletion request (see website/delete-account.html).
 *
 * The user's wardrobe content lives in their own Google Drive and is NOT touched
 * here — they remove that themselves by deleting the LibreLookAI folder / revoking
 * Drive access. This script only removes data held on our side: the Firebase Auth
 * record and any `users/{uid}` Firestore doc + `ledger` subcollection (managed mode).
 *
 * Auth: uses Application Default Credentials, same as seed.ts. Either
 *   export GOOGLE_APPLICATION_CREDENTIALS=/path/to/service-account.json
 * or run `gcloud auth application-default login` first.
 *
 * Build then run:
 *   cd firebase/functions && npm run build
 *   node lib/deleteUser.js user@example.com          # dry run (prints what it would delete)
 *   node lib/deleteUser.js user@example.com --yes     # actually delete
 *   node lib/deleteUser.js --uid <uid> --yes          # by uid instead of email
 */
import * as admin from "firebase-admin";

async function deleteLedger(db: admin.firestore.Firestore, uid: string): Promise<number> {
  const col = db.collection("users").doc(uid).collection("ledger");
  let deleted = 0;
  // Page through the subcollection in batches so large ledgers don't blow memory.
  while (true) {
    const snap = await col.limit(400).get();
    if (snap.empty) break;
    const batch = db.batch();
    snap.docs.forEach((d) => batch.delete(d.ref));
    await batch.commit();
    deleted += snap.size;
    if (snap.size < 400) break;
  }
  return deleted;
}

async function main() {
  const args = process.argv.slice(2);
  const yes = args.includes("--yes") || args.includes("-y");
  const uidFlagIdx = args.indexOf("--uid");
  const uidArg = uidFlagIdx >= 0 ? args[uidFlagIdx + 1] : undefined;
  const email = args.find((a) => !a.startsWith("-") && a !== uidArg);

  if (!email && !uidArg) {
    console.error("Usage: node lib/deleteUser.js <email> [--yes]");
    console.error("       node lib/deleteUser.js --uid <uid> [--yes]");
    process.exit(1);
  }

  admin.initializeApp();
  const auth = admin.auth();
  const db = admin.firestore();

  // Resolve the user record.
  let user: admin.auth.UserRecord;
  try {
    user = uidArg ? await auth.getUser(uidArg) : await auth.getUserByEmail(email!);
  } catch (e: any) {
    console.error(`No Firebase Auth user found for ${uidArg ?? email}: ${e.message}`);
    process.exit(2);
  }

  const uid = user.uid;
  console.log("Found user:");
  console.log(`  uid:           ${uid}`);
  console.log(`  email:         ${user.email ?? "<none>"}`);
  console.log(`  created:       ${user.metadata.creationTime}`);
  console.log(`  last sign-in:  ${user.metadata.lastSignInTime ?? "<never>"}`);

  // Peek at Firestore side data (managed mode only; BYOK has none).
  const userDoc = await db.collection("users").doc(uid).get();
  const ledgerCount = (await db.collection("users").doc(uid).collection("ledger").count().get())
    .data().count;
  console.log(`  users/${uid} doc: ${userDoc.exists ? "exists" : "<none>"}`);
  console.log(`  ledger entries:   ${ledgerCount}`);

  if (!yes) {
    console.log("\nDry run. Re-run with --yes to delete the Auth account and the Firestore data above.");
    return;
  }

  // 1) Firestore ledger subcollection + user doc.
  if (ledgerCount > 0) {
    const n = await deleteLedger(db, uid);
    console.log(`Deleted ${n} ledger entries.`);
  }
  if (userDoc.exists) {
    await db.collection("users").doc(uid).delete();
    console.log(`Deleted users/${uid}.`);
  }

  // 2) Firebase Auth record.
  await auth.deleteUser(uid);
  console.log(`Deleted Firebase Auth account ${uid} (${user.email ?? "no email"}).`);

  console.log(
    "\nDone. Note: the user's wardrobe content is in their own Google Drive and is " +
      "not affected by this script. Firebase Analytics data is deleted separately " +
      "(Analytics user-deletion / retention settings).",
  );
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
