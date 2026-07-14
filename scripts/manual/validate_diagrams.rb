#!/usr/bin/env ruby

require "rexml/document"

root = File.expand_path("../..", __dir__)
assets = Dir[File.join(root, "docs/manual/assets/**/*.svg")].sort
errors = []
expected_markers = %w[amber cyan purple rose teal].map { |color| "arrow-#{color}" }.sort

def png_dimensions(path)
  signature, chunk_length, chunk_type, width, height = File.binread(path, 24).unpack("a8Na4NN")
  return unless signature == "\x89PNG\r\n\x1A\n".b && chunk_length == 13 && chunk_type == "IHDR"

  [width, height]
end

errors << "expected 5 SVG diagrams, found #{assets.length}" unless assets.length == 5
assets.each do |svg_path|
  relative = svg_path.delete_prefix("#{root}/")
  png_path = svg_path.sub(/\.svg\z/, ".png")
  errors << "#{relative}: paired PNG missing" unless File.file?(png_path)
  if File.file?(png_path)
    dimensions = png_dimensions(png_path)
    errors << "#{relative}: expected CairoSVG 2x PNG at 3200x2080, found #{dimensions.inspect}" unless dimensions == [3200, 2080]
  end
  document = REXML::Document.new(File.read(svg_path))
  svg = document.root
  errors << "#{relative}: expected 1600x1040 SVG" unless svg.attributes["width"] == "1600" && svg.attributes["height"] == "1040"
  errors << "#{relative}: accessible title missing" unless REXML::XPath.first(document, "/svg/title")
  errors << "#{relative}: accessible description missing" unless REXML::XPath.first(document, "/svg/desc")
  paths = REXML::XPath.match(document, "//path[@marker-end]")
  errors << "#{relative}: no directed connectors" if paths.empty?
  errors << "#{relative}: rounded orthogonal Q bends missing" unless paths.any? { |path| path.attributes["d"].to_s.match?(/\bQ/) }
  paths.each do |path|
    errors << "#{relative}: connector #{path.attributes['id']} is thinner than 4" if path.attributes["stroke-width"].to_f < 4
    marker_id = path.attributes["marker-end"].to_s[/#([^\)]+)/, 1]
    errors << "#{relative}: connector #{path.attributes['id']} does not use an explicit per-color marker" unless expected_markers.include?(marker_id)
  end
  cards = REXML::XPath.match(document, "//rect[contains(concat(' ', normalize-space(@class), ' '), ' card ')]")
  errors << "#{relative}: no auditable card rectangles" if cards.empty?
  markers = REXML::XPath.match(document, "//marker")
  marker_ids = markers.map { |marker| marker.attributes["id"] }.sort
  errors << "#{relative}: expected per-color markers #{expected_markers.join(', ')}, found #{marker_ids.join(', ')}" unless marker_ids == expected_markers
  markers.each do |marker|
    width = marker.attributes["markerWidth"].to_f
    height = marker.attributes["markerHeight"].to_f
    errors << "#{relative}: marker #{marker.attributes['id']} must be exactly 14x14" unless width == 14 && height == 14
    errors << "#{relative}: marker #{marker.attributes['id']} must use fixed user-space units" unless marker.attributes["markerUnits"] == "userSpaceOnUse"
  end
end

abort(errors.join("\n")) unless errors.empty?
puts "Leader diagram contract passed: #{assets.length} SVG/PNG pairs, 3200x2080 PNGs, accessible cards, rounded connectors, and explicit 14x14 per-color arrowheads."
