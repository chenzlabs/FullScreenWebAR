# ARDisplay methods

# getPointCloud()

Returns a JavaScript Object with , position, orientation, and points.
- timestamp (for change detection)
- position (array [x, y, z])
- orientation (array [x, y, z, w])
- points (array of numbers, which are the x, y, and z values for each point)

# getAmbientLightEstimate()

Returns a number representing the ambient light intensity estimate. (from 0 to 1, I believe)
