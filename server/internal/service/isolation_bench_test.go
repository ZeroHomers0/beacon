package service_test

import (
	"beacon.local/server/internal/domain"
	"beacon.local/server/internal/service"
	"context"
	"errors"
	"reflect"
	"sync"
	"testing"
	"time"
)

type isolationQueue struct {
	tasks []domain.PublishTask
	mu    sync.Mutex
}

func (q *isolationQueue) ClaimPublish(_ int64, limit int) ([]domain.PublishTask, error) {
	q.mu.Lock()
	defer q.mu.Unlock()
	if limit > len(q.tasks) {
		limit = len(q.tasks)
	}
	tasks := q.tasks[:limit]
	q.tasks = q.tasks[limit:]
	return tasks, nil
}
func (q *isolationQueue) Publishable(domain.PublishTask, int64) (bool, error) { return true, nil }
func (q *isolationQueue) CompletePublish(domain.PublishTask, bool, int64) (bool, error) {
	return true, nil
}
func isolationTasks() []domain.PublishTask {
	until := time.Now().Add(time.Minute).UnixMilli()
	return []domain.PublishTask{
		{DeviceID: "slow", LeaseUntil: until, Message: domain.Message{Sequence: 1}},
		{DeviceID: "normal", LeaseUntil: until, Message: domain.Message{Sequence: 2}},
		{DeviceID: "normal", LeaseUntil: until, Message: domain.Message{Sequence: 3}},
	}
}

// Controlled network-stall simulation. It measures head-of-line waiting, not production throughput.
func BenchmarkSlowDeviceHeadOfLine(b *testing.B) {
	for _, test := range []struct {
		name  string
		drain func(context.Context, service.DeliveryQueue, service.Publisher, int) (service.DeliveryResult, error)
	}{
		{"serial", service.DrainDelivery}, {"isolated", service.DrainDeliveryIsolated},
	} {
		b.Run(test.name, func(b *testing.B) {
			var total time.Duration
			for i := 0; i < b.N; i++ {
				queue := &isolationQueue{tasks: isolationTasks()}
				start := time.Now()
				var once sync.Once
				publisher := testPublisher(func(ctx context.Context, _, device string, _ domain.Message) error {
					if device == "slow" {
						select {
						case <-time.After(40 * time.Millisecond):
						case <-ctx.Done():
							return ctx.Err()
						}
					} else {
						time.Sleep(time.Millisecond)
						once.Do(func() { total += time.Since(start) })
					}
					return nil
				})
				if _, err := test.drain(context.Background(), queue, publisher, 4); err != nil {
					b.Fatal(err)
				}
			}
			b.ReportMetric(float64(total.Nanoseconds())/float64(b.N), "normal_wait_ns/op")
		})
	}
}

func TestIsolatedDeliveryPreservesDeviceOrderWhileAnotherDeviceIsBlocked(t *testing.T) {
	queue := &isolationQueue{tasks: isolationTasks()}
	releaseSlow := make(chan struct{})
	normalDone := make(chan struct{})
	done := make(chan error, 1)
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	var sequences []int64
	publisher := testPublisher(func(ctx context.Context, _, device string, message domain.Message) error {
		if device == "slow" {
			select {
			case <-releaseSlow:
				return nil
			case <-ctx.Done():
				return ctx.Err()
			}
		}
		sequences = append(sequences, message.Sequence)
		if len(sequences) == 2 {
			close(normalDone)
		}
		return nil
	})
	go func() {
		r, e := service.DrainDeliveryIsolated(ctx, queue, publisher, 100)
		if e == nil && r.Published != 3 {
			e = errors.New("incomplete round")
		}
		done <- e
	}()
	select {
	case <-normalDone:
	case <-ctx.Done():
		t.Fatal("normal device waited behind a blocked device")
	}
	close(releaseSlow)
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(sequences, []int64{2, 3}) {
		t.Fatalf("device order: %v", sequences)
	}
}
