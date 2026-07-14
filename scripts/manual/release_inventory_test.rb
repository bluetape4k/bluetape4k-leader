require "json"
require "minitest/autorun"
require "tmpdir"

require_relative "release_inventory"

class ReleaseInventoryTest < Minitest::Test
  def test_filters_current_inventory_against_the_release_tree
    Dir.mktmpdir("release-inventory") do |root|
      input = File.join(root, "current.json")
      output = File.join(root, "release.json")
      rows = [
        { "gradlePath" => ":bluetape4k-leader-core", "sourceDir" => "leader-core" },
        { "gradlePath" => ":examples:batch-scheduler", "sourceDir" => "examples/batch-scheduler" },
        { "gradlePath" => ":examples:ktor-app", "sourceDir" => "examples/ktor-app" },
      ]
      File.write(input, JSON.generate(rows))
      runner = lambda do |args|
        case args
        when ["rev-parse", "--verify", "refs/tags/0.4.0^{commit}"] then [("a" * 40) + "\n", true]
        when ["ls-tree", "-r", "--name-only", "a" * 40] then ["examples/ktor-app/build.gradle.kts\n", true]
        else ["", false]
        end
      end
      result = ManualDocs::ReleaseInventory.new(
        repository_root: root, tag: "0.4.0", expected_sha: "a" * 40,
        inventory_path: input, output_path: output, expected_count: 1, git_runner: runner,
      ).write
      assert_equal [":examples:ktor-app"], result.map { |row| row.fetch("gradlePath") }
      assert_equal result, JSON.parse(File.read(output))
      assert File.binread(output).end_with?("\n")
    end
  end

  def test_rejects_duplicate_gradle_paths
    Dir.mktmpdir("release-inventory") do |root|
      input = File.join(root, "current.json")
      rows = 2.times.map { { "gradlePath" => ":duplicate", "sourceDir" => "same" } }
      File.write(input, JSON.generate(rows))
      runner = ->(args) { args.first == "rev-parse" ? [("a" * 40) + "\n", true] : ["same/build.gradle.kts\n", true] }
      error = assert_raises(ManualDocs::ReleaseInventoryError) do
        ManualDocs::ReleaseInventory.new(
          repository_root: root, tag: "0.4.0", expected_sha: "a" * 40,
          inventory_path: input, output_path: File.join(root, "out.json"), expected_count: 2, git_runner: runner,
        ).write
      end
      assert_equal "duplicate Gradle path in release inventory: :duplicate", error.message
    end
  end

  def test_rejects_a_moved_release_tag
    with_inventory([{ "gradlePath" => ":core", "sourceDir" => "core" }]) do |root, input, output|
      runner = ->(_args) { [("b" * 40) + "\n", true] }
      error = assert_raises(ManualDocs::ReleaseInventoryError) do
        inventory(root, input, output, expected_count: 1, runner: runner).write
      end
      assert_equal "release tag 0.4.0 resolves to #{'b' * 40}, expected #{'a' * 40}", error.message
    end
  end

  def test_rejects_an_empty_filtered_release_inventory
    with_inventory([{ "gradlePath" => ":core", "sourceDir" => "core" }]) do |root, input, output|
      runner = release_runner("")
      error = assert_raises(ManualDocs::ReleaseInventoryError) do
        inventory(root, input, output, expected_count: 1, runner: runner).write
      end
      assert_equal "release inventory is empty", error.message
    end
  end

  def test_rejects_a_release_inventory_with_the_wrong_expected_count
    with_inventory([{ "gradlePath" => ":core", "sourceDir" => "core" }]) do |root, input, output|
      runner = release_runner("core/build.gradle.kts\n")
      error = assert_raises(ManualDocs::ReleaseInventoryError) do
        inventory(root, input, output, expected_count: 2, runner: runner).write
      end
      assert_equal "release inventory count 1, expected 2", error.message
    end
  end

  private

  def with_inventory(rows)
    Dir.mktmpdir("release-inventory") do |root|
      input = File.join(root, "current.json")
      output = File.join(root, "release.json")
      File.write(input, JSON.generate(rows))
      yield root, input, output
    end
  end

  def release_runner(tree)
    lambda do |args|
      args.first == "rev-parse" ? [("a" * 40) + "\n", true] : [tree, true]
    end
  end

  def inventory(root, input, output, expected_count:, runner:)
    ManualDocs::ReleaseInventory.new(
      repository_root: root, tag: "0.4.0", expected_sha: "a" * 40,
      inventory_path: input, output_path: output, expected_count: expected_count, git_runner: runner,
    )
  end
end
