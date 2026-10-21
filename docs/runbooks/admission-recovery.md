# Admission recovery runbook

Applies to: the `api` role of `paybridge-core`, health contributor `invoicePointers`
(`InvoicePointersHealthIndicator`, port `ConsistencyCheck`, adapter `MysqlConsistencyCheck`). Spec: T07 admission design, section 4.3.

## What the `invoicePointers` readiness check is

At startup the check runs the DMR recovery queries A to C once and keeps their counts:

- `pointersToMissingFailedOrForeign` (A): an invoice points to a payment that is missing, FAILED or belongs to another invoice.
- `activePaymentsNotPointedTo` (B): a non-FAILED payment that its invoice does not point to.
- `reservationsWithoutPayment` (C): a `payment_request` reservation whose payment does not exist.

`invoicePointers` is part of the readiness group (`readinessState,schemaVersion,invoicePointers`). Any count above zero makes it
DOWN, so the pod never takes admission traffic on a damaged database. If the queries cannot run at all (for example an empty
schema before the migrate role has run), it is DOWN with the single detail `check=failed`.

## It runs once

The check runs only on `ApplicationReadyEvent`. It is never repeated, so after a failed or dirty run the pod stays unready until it
is restarted, even if the data is repaired in the meantime. Repair first, then restart the pod.

## Reading the result

- Use the counts only: the health details of `invoicePointers` carry the three counts (or `check=failed`), never ids or
  exception messages. The startup error log line carries the same counts, or only the exception class name for a failed run.
- Health details are shown only where `management.endpoint.health.show-details` allows it; the default hides them.
- The counts say which invariant is broken, not which rows. Finding the rows is a database task, below.

## Fixing the data

Fixing the data requires the DMR recovery queries A to C, run with the DMR's guidance against the writer, followed by the
DMR's repair steps for each invariant. Do not edit invoice pointers, payments or reservations by hand outside that procedure.
After the repair, restart the pod and confirm `invoicePointers` is UP.
