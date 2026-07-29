from collections import Counter

from scripts.profile_token_lengths import batches, percentile


def test_percentile_uses_nearest_rank():
    histogram = Counter({3: 5, 8: 4, 20: 1})

    assert percentile(histogram, 10, 0.5) == 3
    assert percentile(histogram, 10, 0.9) == 8
    assert percentile(histogram, 10, 0.99) == 20


def test_batches_keeps_final_partial_batch():
    assert list(batches((str(i) for i in range(5)), 2)) == [
        ["0", "1"],
        ["2", "3"],
        ["4"],
    ]
