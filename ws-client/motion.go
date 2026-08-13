package main

import "image"

// yDiff returns the mean absolute difference of the Y plane between two
// decoded frames, sampled coarsely. Non-YCbCr (or differently sized) images
// return 0 so motion detection stays off for exotic streams.
func yDiff(a, b image.Image) float64 {
	ya, ok1 := a.(*image.YCbCr)
	yb, ok2 := b.(*image.YCbCr)
	if !ok1 || !ok2 || ya.YStride != yb.YStride || !ya.Bounds().Eq(yb.Bounds()) {
		return 0
	}
	b0 := ya.Bounds()
	w, h := b0.Dx(), b0.Dy()
	if w < 4 || h < 4 {
		return 0
	}
	step := 16
	if (w/step)*(h/step) < 64 {
		step = 8
	}
	var total float64
	n := 0
	for y := b0.Min.Y; y < b0.Max.Y; y += step {
		for x := b0.Min.X; x < b0.Max.X; x += step {
			i := ya.YOffset(x, y)
			d := float64(ya.Y[i]) - float64(yb.Y[i])
			if d < 0 {
				d = -d
			}
			total += d
			n++
		}
	}
	if n == 0 {
		return 0
	}
	return total / float64(n)
}
