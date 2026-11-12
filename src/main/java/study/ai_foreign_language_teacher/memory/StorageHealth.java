package study.ai_foreign_language_teacher.memory;

public interface StorageHealth {

    /** true, если хранилище отвечает. */
    boolean ready();

    String kind();
}
