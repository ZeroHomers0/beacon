package service

import (
	"context"
	"errors"
	"testing"
	"time"
)

type retentionFake struct {
	calls   int
	cleanup func(context.Context, int64, int) (int64, error)
}

func (f *retentionFake) CleanupConfirmedBatch(ctx context.Context, cutoff int64, limit int) (int64, error) {
	f.calls++
	return f.cleanup(ctx, cutoff, limit)
}

func TestRetentionDisabledAndCancelledNeverTouchStore(t *testing.T) {
	f := &retentionFake{}
	RunHistoryRetention(context.Background(), f, 0, time.Minute, time.Second, 100, nil)
	RunHistoryRetention(context.Background(), f, time.Hour, 0, time.Second, 100, nil)
	RunHistoryRetention(context.Background(), f, time.Hour, time.Minute, time.Second, 101, nil)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	RunHistoryRetention(ctx, f, time.Hour, time.Minute, time.Second, 100, nil)
	if f.calls != 0 {
		t.Fatal("disabled/cancelled worker wrote")
	}
}

func TestRetentionRetriesFailedBatchAndStopsOnCancellation(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	f := &retentionFake{}
	f.cleanup = func(batch context.Context, cutoff int64, limit int) (int64, error) {
		if _, ok := batch.Deadline(); !ok {
			t.Fatal("unbounded batch")
		}
		age := time.Now().UnixMilli() - cutoff
		if age < time.Hour.Milliseconds()-100 || age > time.Hour.Milliseconds()+100 || limit != 100 {
			t.Fatal("bad retention bounds")
		}
		if f.calls == 1 {
			return 0, errors.New("injected failure")
		}
		return 3, nil
	}
	observed := 0
	RunHistoryRetention(ctx, f, time.Hour, time.Millisecond, time.Second/2, 100, func(deleted int64, err error) {
		observed++
		if observed == 1 && err == nil {
			t.Fatal("missing failure")
		}
		if observed == 2 {
			if deleted != 3 || err != nil {
				t.Fatal("retry failed")
			}
			cancel()
		}
	})
	if f.calls != 2 || observed != 2 {
		t.Fatal("unexpected calls", f.calls, observed)
	}
}

func TestRetentionBatchDeadlineIsReportedWithoutContinuingAfterShutdown(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	f := &retentionFake{cleanup: func(batch context.Context, _ int64, _ int) (int64, error) {
		<-batch.Done()
		return 0, batch.Err()
	}}
	observed := 0
	RunHistoryRetention(ctx, f, time.Hour, time.Minute, time.Millisecond, 100, func(deleted int64, err error) {
		observed++
		if deleted != 0 || !errors.Is(err, context.DeadlineExceeded) {
			t.Fatal("batch timeout lost", err)
		}
		cancel()
	})
	if f.calls != 1 || observed != 1 {
		t.Fatal("worker continued after cancellation")
	}
}
